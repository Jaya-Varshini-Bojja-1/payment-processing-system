package com.portfolio.paymentsystem.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Issued token pair")
public record AuthResponse(

        @Schema(description = "Short-lived JWT sent as 'Authorization: Bearer <token>'")
        String accessToken,

        @Schema(description = "Opaque long-lived token; send to /auth/refresh to rotate")
        String refreshToken,

        @Schema(example = "Bearer")
        String tokenType,

        @Schema(description = "Access token lifetime in seconds", example = "900")
        long expiresInSeconds
) {
    public static AuthResponse of(String accessToken, String refreshToken, long expiresInSeconds) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresInSeconds);
    }
}
