package com.portfolio.paymentsystem.wallet.controller;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * End-to-end M2 account + ledger-history flow against the real host-local
 * PostgreSQL 18, exercised through the actual HTTP layer (auth included) —
 * the closest thing to "does this really work" short of a manual Postman
 * run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
class AccountControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueEmail() {
        return "wallet-it-" + UUID.randomUUID() + "@example.com";
    }

    /** Registers a fresh user and returns their access token. */
    private String registerAndLogin() throws Exception {
        String email = uniqueEmail();
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
        JsonNode body = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    @Test
    void openAccountCreatesZeroBalanceInrWallet() throws Exception {
        String accessToken = registerAndLogin();

        mockMvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance.amountPaise").value(0))
                .andExpect(jsonPath("$.balance.currency").value("INR"));
    }

    @Test
    void openingASecondAccountForTheSameUserIsRejectedWith409() throws Exception {
        String accessToken = registerAndLogin();
        mockMvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));
    }

    @Test
    void getMeReturns404BeforeAnyAccountExists() throws Exception {
        String accessToken = registerAndLogin();

        mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void getMeReturnsAccountAfterCreation() throws Exception {
        String accessToken = registerAndLogin();
        mockMvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.amountPaise").value(0));
    }

    @Test
    void ledgerHistoryStartsEmptyForANewAccount() throws Exception {
        String accessToken = registerAndLogin();
        MvcResult createResult = mockMvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andReturn();
        String accountId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/accounts/{id}/ledger", accountId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void userCannotReadAnotherUsersAccountLedger() throws Exception {
        String ownerToken = registerAndLogin();
        MvcResult createResult = mockMvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isCreated())
                .andReturn();
        String ownerAccountId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        String otherUserToken = registerAndLogin();
        mockMvc.perform(get("/api/v1/accounts/{id}/ledger", ownerAccountId).header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ACCESS_DENIED"));
    }

    @Test
    void accountEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/accounts"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/accounts/me"))
                .andExpect(status().isUnauthorized());
    }
}
