package com.portfolio.paymentsystem.common.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Payment Processing System API",
        version = "v1",
        description = "INR wallet and money-movement backend (modular monolith). "
                + "Covers JWT authentication, wallet accounts, double-entry ledger history, "
                + "synchronous P2P transfers, and asynchronous gateway-backed deposits/withdrawals "
                + "(transactional outbox -> Kafka -> simulated gateway). Money-moving POSTs require an "
                + "Idempotency-Key header and are rate limited per user."))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {
}
