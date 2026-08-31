package com.portfolio.paymentsystem.identity.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.portfolio.paymentsystem.identity.domain.User;
import com.portfolio.paymentsystem.identity.dto.UserResponse;
import com.portfolio.paymentsystem.identity.repository.UserRepository;
import com.portfolio.paymentsystem.identity.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * M1's only protected endpoint: proves the JWT filter chain works end to
 * end. Real domain endpoints (wallets, transfers, payments) arrive in M2+.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Authenticated user profile")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    @Operation(summary = "Get the current authenticated user", description = "Requires a valid, non-denylisted access token.")
    @ApiResponse(responseCode = "200", description = "Current user")
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or denylisted access token")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists: " + principal.userId()));
        return UserResponse.from(user);
    }
}
