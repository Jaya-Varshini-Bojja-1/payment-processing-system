package com.portfolio.paymentsystem.identity.controller;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
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
 * End-to-end M1 auth flow against the real (host-local) PostgreSQL 18 and
 * Redis (Memurai) instances the app uses — no Testcontainers, per the
 * project plan. Skipped automatically when those credentials aren't present
 * in the environment (e.g. a plain `mvn test` on a machine without them).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueEmail() {
        return "it-" + UUID.randomUUID() + "@example.com";
    }

    private JsonNode register(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void registerCreatesUserWithUserRole() throws Exception {
        String email = uniqueEmail();

        JsonNode body = register(email, "Str0ngPassw0rd!");

        org.assertj.core.api.Assertions.assertThat(body.get("email").asText()).isEqualTo(email);
        org.assertj.core.api.Assertions.assertThat(body.get("role").asText()).isEqualTo("USER");
    }

    @Test
    void registerRejectsDuplicateEmailWith409() throws Exception {
        String email = uniqueEmail();
        register(email, "Str0ngPassw0rd!");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "Str0ngPassw0rd!"}
                                """.formatted(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void registerRejectsInvalidPayloadWith400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "password": "short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void loginRejectsWrongPasswordWith401() throws Exception {
        String email = uniqueEmail();
        register(email, "Str0ngPassw0rd!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "WrongPassword1"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void fullFlow_registerLoginMeRefreshLogout() throws Exception {
        String email = uniqueEmail();
        String password = "Str0ngPassw0rd!";
        register(email, password);

        JsonNode loginBody = login(email, password);
        String accessToken = loginBody.get("accessToken").asText();
        String refreshToken = loginBody.get("refreshToken").asText();
        org.assertj.core.api.Assertions.assertThat(loginBody.get("expiresInSeconds").asLong()).isGreaterThan(0);

        // Protected endpoint succeeds with a valid access token.
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        // Rotate the refresh token.
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", not(blankOrNullString())))
                .andReturn();
        JsonNode refreshBody = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        String rotatedAccessToken = refreshBody.get("accessToken").asText();
        String rotatedRefreshToken = refreshBody.get("refreshToken").asText();
        org.assertj.core.api.Assertions.assertThat(rotatedRefreshToken).isNotEqualTo(refreshToken);

        // Reusing the now-rotated (revoked) refresh token must fail — reuse detection.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // Reuse detection revoked every session, so even the freshly rotated one is now dead.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(rotatedRefreshToken)))
                .andExpect(status().isUnauthorized());

        // Log back in for a clean session to exercise logout.
        JsonNode secondLogin = login(email, password);
        String secondAccessToken = secondLogin.get("accessToken").asText();
        String secondRefreshToken = secondLogin.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + secondAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(secondRefreshToken)))
                .andExpect(status().isNoContent());

        // The just-logged-out access token is now denylisted.
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + secondAccessToken))
                .andExpect(status().isUnauthorized());

        // Its refresh token was revoked on logout too.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(secondRefreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meRejectsTamperedToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }
}
