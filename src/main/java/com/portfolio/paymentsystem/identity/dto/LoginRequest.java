package com.portfolio.paymentsystem.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Login request")
public record LoginRequest(

        @Schema(example = "alice@example.com")
        @NotBlank(message = "email is required")
        String email,

        @Schema(example = "Str0ngPassw0rd!")
        @NotBlank(message = "password is required")
        String password
) {
}
