package com.portfolio.paymentsystem.identity.dto;

import java.time.Instant;
import java.util.UUID;

import com.portfolio.paymentsystem.identity.domain.User;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Authenticated user profile")
public record UserResponse(
        UUID id,
        String email,
        String role,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole().name(), user.getCreatedAt());
    }
}
