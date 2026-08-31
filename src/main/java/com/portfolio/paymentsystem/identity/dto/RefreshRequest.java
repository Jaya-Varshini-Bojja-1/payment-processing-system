package com.portfolio.paymentsystem.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Refresh token rotation request")
public record RefreshRequest(

        @Schema(description = "Opaque refresh token issued at login or a previous refresh")
        @NotBlank(message = "refreshToken is required")
        String refreshToken
) {
}
