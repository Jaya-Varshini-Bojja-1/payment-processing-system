package com.portfolio.paymentsystem.transfer.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * End-to-end M3 P2P transfer flow: real HTTP layer (auth included), real
 * host-local PostgreSQL 18 and Redis. Since M3 has no deposit endpoint yet,
 * "funding" a sender's account is a test-only SQL seed — exactly the
 * pattern {@code LedgerServiceIntegrationTest} already established.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
class TransferControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record UserContext(String accessToken, UUID userId, UUID accountId) {
    }

    private UserContext registerLoginAndOpenAccount() throws Exception {
        String email = "transfer-it-" + UUID.randomUUID() + "@example.com";
        String password = "Str0ngPassw0rd!";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = loginBody.get("accessToken").asText();

        MvcResult meResult = mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID userId = UUID.fromString(objectMapper.readTree(meResult.getResponse().getContentAsString()).get("id").asText());

        MvcResult accountResult = mockMvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID accountId = UUID.fromString(objectMapper.readTree(accountResult.getResponse().getContentAsString()).get("id").asText());

        return new UserContext(accessToken, userId, accountId);
    }

    /** Test fixture only, exactly like {@code LedgerServiceIntegrationTest}: no deposit endpoint exists until M5. */
    private void fund(UUID accountId, long balancePaise) {
        jdbcTemplate.update("UPDATE accounts SET balance_paise = ? WHERE id = ?", balancePaise, accountId);
    }

    private String transferRequestBody(UUID toUserId, long amountPaise) {
        return """
                {"toUserId": "%s", "amountPaise": %d}
                """.formatted(toUserId, amountPaise);
    }

    @Test
    void successfulTransferDebitsSenderCreditsRecipientAndReturns201() throws Exception {
        UserContext sender = registerLoginAndOpenAccount();
        UserContext recipient = registerLoginAndOpenAccount();
        fund(sender.accountId(), 100_000);

        String created = mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(recipient.userId(), 40_000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount.amountPaise").value(40_000))
                .andExpect(jsonPath("$.fromUserId").value(sender.userId().toString()))
                .andExpect(jsonPath("$.toUserId").value(recipient.userId().toString()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getContentAsString();
        UUID transferId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + sender.accessToken()))
                .andExpect(jsonPath("$.balance.amountPaise").value(60_000));
        mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + recipient.accessToken()))
                .andExpect(jsonPath("$.balance.amountPaise").value(40_000));

        // Re-fetches the transfer as a *fresh* read from the database (a brand new
        // request/transaction), rather than trusting the in-memory object echoed
        // back by the create call above. This is what actually catches "the DB row
        // was never updated to COMPLETED" bugs that a same-object round-trip can't.
        mockMvc.perform(get("/api/v1/transfers/{id}", transferId)
                        .header("Authorization", "Bearer " + sender.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void replayingTheSameIdempotencyKeyAndBodyReturns200WithoutMovingMoneyTwice() throws Exception {
        UserContext sender = registerLoginAndOpenAccount();
        UserContext recipient = registerLoginAndOpenAccount();
        fund(sender.accountId(), 100_000);
        String idempotencyKey = UUID.randomUUID().toString();
        String body = transferRequestBody(recipient.userId(), 40_000);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount.amountPaise").value(40_000));

        mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + sender.accessToken()))
                .andExpect(jsonPath("$.balance.amountPaise").value(60_000));
    }

    @Test
    void reusingTheSameIdempotencyKeyWithADifferentBodyReturns422() throws Exception {
        UserContext sender = registerLoginAndOpenAccount();
        UserContext recipient = registerLoginAndOpenAccount();
        fund(sender.accountId(), 100_000);
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(recipient.userId(), 40_000)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(recipient.userId(), 50_000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void missingIdempotencyKeyHeaderReturns400() throws Exception {
        UserContext sender = registerLoginAndOpenAccount();
        UserContext recipient = registerLoginAndOpenAccount();

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(recipient.userId(), 1_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUIRED_HEADER"));
    }

    @Test
    void selfTransferIsRejectedWith400() throws Exception {
        UserContext sender = registerLoginAndOpenAccount();
        fund(sender.accountId(), 100_000);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(sender.userId(), 1_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_TRANSFER_NOT_ALLOWED"));
    }

    @Test
    void transferToAUserWithNoAccountReturns404() throws Exception {
        UserContext sender = registerLoginAndOpenAccount();
        fund(sender.accountId(), 100_000);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(UUID.randomUUID(), 1_000)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_ACCOUNT_NOT_FOUND"));
    }

    @Test
    void insufficientBalanceIsRejectedWith400AndCachedForReplay() throws Exception {
        UserContext sender = registerLoginAndOpenAccount();
        UserContext recipient = registerLoginAndOpenAccount();
        fund(sender.accountId(), 1_000);
        String idempotencyKey = UUID.randomUUID().toString();
        String body = transferRequestBody(recipient.userId(), 50_000);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        // Replaying the exact same failed request must return the identical
        // cached failure rather than re-running the business logic.
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void aUserWithNoAccountCannotInitiateATransfer() throws Exception {
        String email = "transfer-it-noaccount-" + UUID.randomUUID() + "@example.com";
        String password = "Str0ngPassw0rd!";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated());
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asText();

        UserContext recipient = registerLoginAndOpenAccount();

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(recipient.userId(), 1_000)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void getTransferIsVisibleToBothPartiesButNotToAThirdUser() throws Exception {
        UserContext sender = registerLoginAndOpenAccount();
        UserContext recipient = registerLoginAndOpenAccount();
        UserContext stranger = registerLoginAndOpenAccount();
        fund(sender.accountId(), 100_000);

        MvcResult createResult = mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(recipient.userId(), 40_000)))
                .andExpect(status().isCreated())
                .andReturn();
        String transferId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/transfers/{id}", transferId).header("Authorization", "Bearer " + sender.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/transfers/{id}", transferId).header("Authorization", "Bearer " + recipient.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/transfers/{id}", transferId).header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRANSFER_ACCESS_DENIED"));
    }

    @Test
    void getTransferReturns404ForAnUnknownId() throws Exception {
        UserContext user = registerLoginAndOpenAccount();

        mockMvc.perform(get("/api/v1/transfers/{id}", UUID.randomUUID()).header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void listTransfersReturnsTransfersInvolvingTheCaller() throws Exception {
        UserContext sender = registerLoginAndOpenAccount();
        UserContext recipient = registerLoginAndOpenAccount();
        fund(sender.accountId(), 100_000);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(recipient.userId(), 10_000)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/transfers").header("Authorization", "Bearer " + sender.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/transfers").header("Authorization", "Bearer " + recipient.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void transferEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(UUID.randomUUID(), 1_000)))
                .andExpect(status().isUnauthorized());
    }
}
