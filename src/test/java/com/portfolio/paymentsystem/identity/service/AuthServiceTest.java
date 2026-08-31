package com.portfolio.paymentsystem.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.portfolio.paymentsystem.identity.domain.Role;
import com.portfolio.paymentsystem.identity.domain.User;
import com.portfolio.paymentsystem.identity.dto.AuthResponse;
import com.portfolio.paymentsystem.identity.dto.LoginRequest;
import com.portfolio.paymentsystem.identity.dto.RegisterRequest;
import com.portfolio.paymentsystem.identity.exception.EmailAlreadyExistsException;
import com.portfolio.paymentsystem.identity.exception.InvalidCredentialsException;
import com.portfolio.paymentsystem.identity.repository.UserRepository;
import com.portfolio.paymentsystem.identity.security.JwtService;
import com.portfolio.paymentsystem.identity.security.RefreshTokenService;
import com.portfolio.paymentsystem.identity.security.TokenDenylistService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private TokenDenylistService tokenDenylistService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService, refreshTokenService, tokenDenylistService);
    }

    @Test
    void registerCreatesUserWithEncodedPassword() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Str0ngPassw0rd!")).thenReturn("encoded-hash");
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = authService.register(new RegisterRequest("Alice@Example.com", "Str0ngPassw0rd!"));

        assertThat(saved.getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getPasswordHash()).isEqualTo("encoded-hash");
        assertThat(saved.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("alice@example.com", "Str0ngPassw0rd!")))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void loginSucceedsAndIssuesTokenPair() {
        User user = User.newUser("alice@example.com", "encoded-hash");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Str0ngPassw0rd!", "encoded-hash")).thenReturn(true);
        when(jwtService.generateAccessToken(user.getId(), Role.USER))
                .thenReturn(new JwtService.GeneratedAccessToken("access-token", "jti-1", Instant.now().plusSeconds(900)));
        when(refreshTokenService.issueFor(user))
                .thenReturn(new RefreshTokenService.IssuedRefreshToken("refresh-token", Instant.now().plusSeconds(604800)));

        AuthResponse response = authService.login(new LoginRequest("alice@example.com", "Str0ngPassw0rd!"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void loginRejectsUnknownEmail() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "whatever123")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = User.newUser("alice@example.com", "encoded-hash");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "encoded-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice@example.com", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshIssuesNewTokenPairFromRotationResult() {
        UUID userId = UUID.randomUUID();
        when(refreshTokenService.rotate("old-refresh-token"))
                .thenReturn(new RefreshTokenService.RotationResult(userId, Role.USER, "new-refresh-token", Instant.now().plusSeconds(604800)));
        when(jwtService.generateAccessToken(userId, Role.USER))
                .thenReturn(new JwtService.GeneratedAccessToken("new-access-token", "jti-2", Instant.now().plusSeconds(900)));

        AuthResponse response = authService.refresh("old-refresh-token");

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
    }

    @Test
    void logoutRevokesRefreshTokenAndDenylistsJti() {
        Claims claims = mock(Claims.class);
        when(claims.getId()).thenReturn("jti-1");
        when(claims.getExpiration()).thenReturn(java.util.Date.from(Instant.now().plusSeconds(900)));
        when(jwtService.parseAndValidate("access-token")).thenReturn(claims);

        authService.logout("access-token", "refresh-token");

        verify(refreshTokenService).revoke("refresh-token");
        verify(tokenDenylistService).denylist(eq("jti-1"), any());
    }

    @Test
    void logoutStillRevokesRefreshTokenWhenAccessTokenInvalid() {
        when(jwtService.parseAndValidate("bad-token")).thenThrow(new JwtException("bad signature"));

        authService.logout("bad-token", "refresh-token");

        verify(refreshTokenService).revoke("refresh-token");
        verify(tokenDenylistService, never()).denylist(anyString(), any());
    }
}
