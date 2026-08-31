package com.portfolio.paymentsystem.identity.security;

import java.util.UUID;

import com.portfolio.paymentsystem.identity.domain.Role;

/**
 * The Spring Security {@code principal} populated by {@link JwtAuthenticationFilter}
 * from validated JWT claims — deliberately minimal so authenticating a
 * request never requires a database round-trip.
 */
public record AuthenticatedUser(UUID userId, Role role) {
}
