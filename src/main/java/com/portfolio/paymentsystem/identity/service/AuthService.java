package com.portfolio.paymentsystem.identity.service;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

/**
 * Orchestrates the M1 auth use cases. Password/token mechanics live in their
 * own collaborators ({@link JwtService}, {@link RefreshTokenService},
 * {@link TokenDenylistService}); this class only sequences them and enforces
 * the auth-specific business rules (no duplicate emails, generic invalid
 * credentials, etc.).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final TokenDenylistService tokenDenylistService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            TokenDenylistService tokenDenylistService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.tokenDenylistService = tokenDenylistService;
    }

    @Transactional
    public User register(RegisterRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyExistsException(normalizedEmail);
        }

        User user = User.newUser(normalizedEmail, passwordEncoder.encode(request.password()));
        User saved = userRepository.save(user);
        log.info("Registered new user userId={}", saved.getId());
        return saved;
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail)
                .filter(User::isActive)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        log.info("User login succeeded userId={}", user.getId());
        return issueTokenPair(user);
    }

    /**
     * Deliberately NOT {@code @Transactional} here: {@link RefreshTokenService#rotate}
     * owns its own transaction, including a {@code noRollbackFor} rule so
     * that reuse-detection can revoke every session for a user and still
     * throw. If this method were also {@code @Transactional}, the inner
     * call would merely join it, and this method's plain (rollback-on-any-
     * exception) transaction would win, silently undoing that protective
     * revocation. See {@link RefreshTokenService#rotate} for details.
     */
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(rawRefreshToken);
        JwtService.GeneratedAccessToken accessToken = jwtService.generateAccessToken(rotation.userId(), rotation.role());
        log.info("Refresh token rotated userId={}", rotation.userId());
        return AuthResponse.of(accessToken.token(), rotation.rawToken(), secondsUntil(accessToken.expiresAt()));
    }

    /**
     * Revokes the refresh token used for this session and denylists the
     * current access token's {@code jti} so it cannot be used again even
     * though it has not yet naturally expired.
     */
    @Transactional
    public void logout(String rawAccessToken, String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);

        try {
            Claims claims = jwtService.parseAndValidate(rawAccessToken);
            tokenDenylistService.denylist(claims.getId(), claims.getExpiration().toInstant());
        } catch (JwtException e) {
            // Token was already invalid/expired — nothing further to denylist.
            log.debug("Logout presented an already-invalid access token: {}", e.getMessage());
        }
    }

    private AuthResponse issueTokenPair(User user) {
        JwtService.GeneratedAccessToken accessToken = jwtService.generateAccessToken(user.getId(), user.getRole());
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issueFor(user);
        return AuthResponse.of(accessToken.token(), refreshToken.rawToken(), secondsUntil(accessToken.expiresAt()));
    }

    private static long secondsUntil(Instant instant) {
        return Duration.between(Instant.now(), instant).getSeconds();
    }
}
