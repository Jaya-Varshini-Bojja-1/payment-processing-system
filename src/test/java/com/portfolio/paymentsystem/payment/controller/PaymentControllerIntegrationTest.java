package com.portfolio.paymentsystem.payment.controller;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The acceptance/idempotency HTTP layer for M5, with the outbox publisher
 * and Kafka listener left disabled (the test classpath default -- see
 * {@code src/test/resources/application.yml}): a created payment is
 * expected to sit at {@code PENDING} forever in this test class, exactly
 * the same "durably accepted, not yet processed" contract a real client
 * would observe if it queried immediately after a 202. The full
 * gateway-to-ledger path is covered separately by
 * {@code PaymentFullFlowIntegrationTest} against a real Kafka broker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private record UserContext(String accessToken, UUID userId) {
    }

    private UserContext registerLoginAndOpenAccount() throws Exception {
        String email = "payment-it-" + UUID.randomUUID() + "@example.com";
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

        mockMvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated());

        return new UserContext(accessToken, userId);
    }

    private String amountBody(long amountPaise) {
        return """
                {"amountPaise": %d}
                """.formatted(amountPaise);
    }

    @Test
    void depositIsAcceptedAsPendingAndVisibleOnGet() throws Exception {
        UserContext user = registerLoginAndOpenAccount();

        String created = mockMvc.perform(post("/api/v1/payments/deposits")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amountBody(50_000)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount.amountPaise").value(50_000))
                .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        mockMvc.perform(get("/api/v1/payments/{id}", paymentId).header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void withdrawalWithInsufficientBalanceIsRejectedWith400AndNeverAccepted() throws Exception {
        UserContext user = registerLoginAndOpenAccount();

        mockMvc.perform(post("/api/v1/payments/withdrawals")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amountBody(50_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void replayingTheSameIdempotencyKeyAndBodyReturns200WithTheSamePaymentId() throws Exception {
        UserContext user = registerLoginAndOpenAccount();
        String idempotencyKey = UUID.randomUUID().toString();
        String body = amountBody(50_000);

        String created = mockMvc.perform(post("/api/v1/payments/deposits")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String paymentId = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/v1/payments/deposits")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId));
    }

    @Test
    void reusingTheSameIdempotencyKeyWithADifferentBodyReturns422() throws Exception {
        UserContext user = registerLoginAndOpenAccount();
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/payments/deposits")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amountBody(50_000)))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/payments/deposits")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amountBody(60_000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void missingIdempotencyKeyHeaderReturns400() throws Exception {
        UserContext user = registerLoginAndOpenAccount();

        mockMvc.perform(post("/api/v1/payments/deposits")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amountBody(50_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUIRED_HEADER"));
    }

    @Test
    void getPaymentIsForbiddenForAnotherUserAndUnknownIdReturns404() throws Exception {
        UserContext owner = registerLoginAndOpenAccount();
        UserContext stranger = registerLoginAndOpenAccount();

        String created = mockMvc.perform(post("/api/v1/payments/deposits")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amountBody(20_000)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String paymentId = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(get("/api/v1/payments/{id}", paymentId).header("Authorization", "Bearer " + stranger.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PAYMENT_ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID()).header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void listPaymentsReturnsOnlyTheCallersOwnPaymentsFilteredByType() throws Exception {
        UserContext user = registerLoginAndOpenAccount();

        mockMvc.perform(post("/api/v1/payments/deposits")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amountBody(10_000)))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/payments").header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/payments").queryParam("type", "WITHDRAWAL")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void paymentEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/payments/deposits")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amountBody(1_000)))
                .andExpect(status().isUnauthorized());
    }
}
