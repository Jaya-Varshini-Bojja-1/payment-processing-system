package com.portfolio.paymentsystem.common.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * End-to-end proof of the M6 rate limiter wiring: real HTTP layer (auth
 * included), real host-local Redis for the counter, and the
 * {@code RateLimitExceededException} -&gt; {@code GlobalExceptionHandler}
 * -&gt; {@code 429} response path. The limit/window are shrunk via
 * {@link TestPropertySource} so the test runs in a couple of seconds
 * instead of needing a full minute to observe the window reset.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.rate-limit.limit=3",
        "app.rate-limit.window=PT2S"
})
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
class RateLimitControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record UserContext(String accessToken, UUID userId, UUID accountId) {
    }

    private UserContext registerLoginAndOpenAccount() throws Exception {
        String email = "ratelimit-it-" + UUID.randomUUID() + "@example.com";
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

    /** Test fixture only, same pattern as {@code TransferControllerIntegrationTest}: no deposit endpoint funds accounts here. */
    private void fund(UUID accountId, long balancePaise) {
        jdbcTemplate.update("UPDATE accounts SET balance_paise = ? WHERE id = ?", balancePaise, accountId);
    }

    private String transferRequestBody(UUID toUserId, long amountPaise) {
        return """
                {"toUserId": "%s", "amountPaise": %d}
                """.formatted(toUserId, amountPaise);
    }

    private MvcResult createTransfer(UserContext sender, UUID recipientUserId) throws Exception {
        return mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(recipientUserId, 1_000)))
                .andReturn();
    }

    @Test
    void theFourthTransferWithinTheWindowIsRejectedWith429AndRetryAfter() throws Exception {
        UserContext sender = registerLoginAndOpenAccount();
        UserContext recipient = registerLoginAndOpenAccount();
        fund(sender.accountId(), 10_000_000);

        for (int i = 0; i < 3; i++) {
            createTransfer(sender, recipient.userId());
        }

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(recipient.userId(), 1_000)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void aNewRequestSucceedsAgainOnceTheWindowElapses() throws Exception {
        UserContext sender = registerLoginAndOpenAccount();
        UserContext recipient = registerLoginAndOpenAccount();
        fund(sender.accountId(), 10_000_000);

        for (int i = 0; i < 3; i++) {
            createTransfer(sender, recipient.userId());
        }
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(recipient.userId(), 1_000)))
                .andExpect(status().isTooManyRequests());

        Thread.sleep(2_500);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(recipient.userId(), 1_000)))
                .andExpect(status().isCreated());
    }

    /**
     * Each {@link RateLimitScope} is an isolated counter — exhausting the
     * transfer-create budget must not block a completely different
     * money-moving endpoint for the same user.
     */
    @Test
    void ratelimitingATransferDoesNotAffectAnUnrelatedScope() throws Exception {
        UserContext sender = registerLoginAndOpenAccount();
        UserContext recipient = registerLoginAndOpenAccount();
        fund(sender.accountId(), 10_000_000);

        for (int i = 0; i < 3; i++) {
            createTransfer(sender, recipient.userId());
        }
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequestBody(recipient.userId(), 1_000)))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/v1/payments/deposits")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountPaise": 5000}
                                """))
                .andExpect(status().isAccepted());
    }
}
