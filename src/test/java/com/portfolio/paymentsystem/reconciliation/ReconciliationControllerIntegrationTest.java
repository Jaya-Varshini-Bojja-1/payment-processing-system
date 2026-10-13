package com.portfolio.paymentsystem.reconciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.portfolio.paymentsystem.identity.domain.Role;
import com.portfolio.paymentsystem.identity.security.JwtService;

/**
 * Authorization of {@code GET /api/v1/admin/reconciliation}, through the real
 * security filter chain with genuine signed JWTs (the filter derives
 * {@code ROLE_<role>} from the token's role claim, so no user rows are
 * needed and nothing is written to the database).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
class ReconciliationControllerIntegrationTest {

    private static final String PATH = "/api/v1/admin/reconciliation";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    private String bearer(Role role) {
        return "Bearer " + jwtService.generateAccessToken(UUID.randomUUID(), role).token();
    }

    @Test
    void unauthenticatedRequestGets401() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userRoleGets403() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", bearer(Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminRoleGets200WithTheSixCheckReport() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.clean").isBoolean())
                .andExpect(jsonPath("$.errorCount").isNumber())
                .andExpect(jsonPath("$.warningCount").isNumber())
                .andExpect(jsonPath("$.checks.length()").value(6))
                .andExpect(jsonPath("$.checks[0].checkId").value("R1"))
                .andExpect(jsonPath("$.checks[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.checks[5].checkId").value("R6"))
                .andExpect(jsonPath("$.checks[5].severity").value("WARNING"));
    }
}
