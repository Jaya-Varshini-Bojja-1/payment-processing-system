package com.portfolio.paymentsystem.identity.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.portfolio.paymentsystem.identity.domain.User;
import com.portfolio.paymentsystem.identity.dto.AuthResponse;
import com.portfolio.paymentsystem.identity.dto.LoginRequest;
import com.portfolio.paymentsystem.identity.dto.RefreshRequest;
import com.portfolio.paymentsystem.identity.dto.RegisterRequest;
import com.portfolio.paymentsystem.identity.dto.UserResponse;
import com.portfolio.paymentsystem.identity.service.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Registration, login, refresh, and logout")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user", description = "Creates a USER-role account with a BCrypt-hashed password.")
    @ApiResponse(responseCode = "201", description = "User created")
    @ApiResponse(responseCode = "409", description = "Email already registered")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Returns a short-lived access token and a rotating refresh token.")
    @ApiResponse(responseCode = "200", description = "Login succeeded")
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token", description = "Consumes the given refresh token and issues a new access/refresh pair. Reuse of an already-rotated token revokes all sessions for that user.")
    @ApiResponse(responseCode = "200", description = "New token pair issued")
    @ApiResponse(responseCode = "401", description = "Refresh token invalid, expired, or revoked")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Log out", description = "Revokes the given refresh token and denylists the current access token's jti in Redis until it would have expired naturally.")
    @ApiResponse(responseCode = "204", description = "Logged out")
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
    public ResponseEntity<Void> logout(
            @RequestHeader("Authorization") String authorizationHeader,
            @Valid @RequestBody RefreshRequest request) {
        String accessToken = authorizationHeader.startsWith(BEARER_PREFIX)
                ? authorizationHeader.substring(BEARER_PREFIX.length())
                : authorizationHeader;
        authService.logout(accessToken, request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
