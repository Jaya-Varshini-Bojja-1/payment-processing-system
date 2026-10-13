package com.portfolio.paymentsystem.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Turns on {@code @PreAuthorize} checks. Kept separate from
 * {@code SecurityConfig} (the URL-level filter chain) so enabling
 * method-level authorization does not touch the existing authentication
 * setup. Today only the admin reconciliation endpoint uses it; the JWT
 * filter already grants {@code ROLE_<role>} authorities, which is what
 * {@code hasRole('ADMIN')} checks.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}
