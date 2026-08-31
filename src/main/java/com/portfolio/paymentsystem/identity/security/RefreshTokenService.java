package com.portfolio.paymentsystem.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.portfolio.paymentsystem.identity.domain.RefreshToken;
import com.portfolio.paymentsystem.identity.domain.Role;
import com.portfolio.paymentsystem.identity.domain.User;
import com.portfolio.paymentsystem.identity.exception.InvalidRefreshTokenException;
import com.portfolio.paymentsystem.identity.repository.RefreshTokenRepository;

/**
 * Issues, rotates, and revokes refresh tokens.
 * <p>
 * Tokens are opaque random secrets; only their SHA-256 hash is ever
 * persisted (see {@link #hash(String)}). SHA-256, not BCrypt, is used
 * deliberately: unlike passwords, refresh tokens are already high-entropy
 * (256 random bits) and not human-guessable, so a fast cryptographic hash is
 * sufficient and avoids paying BCrypt's deliberate slowness on every refresh
 * call.
 * <p>
 * Rotation: presenting a valid refresh token revokes it and issues a new
 * one, linked via {@code replacedByTokenId}. Presenting an already-revoked
 * token is treated as reuse (possible theft) and revokes every active token
 * for that user, forcing re-authentication everywhere.
 */
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32; // 256 bits

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, RefreshTokenProperties properties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
    }

    @Transactional
    public IssuedRefreshToken issueFor(User user) {
        String rawToken = generateRawToken();
        Instant expiresAt = Instant.now().plus(properties.getTtl());
        RefreshToken token = RefreshToken.issue(user, hash(rawToken), expiresAt);
        refreshTokenRepository.save(token);
        return new IssuedRefreshToken(rawToken, expiresAt);
    }

    /**
     * Validates the presented refresh token and rotates it: revokes the old
     * row and issues a new one for the same user.
     * <p>
     * {@code noRollbackFor} is deliberate: the reuse-detection branch below
     * revokes every active session for the user and then throws. Without
     * this, Spring's default "roll back on RuntimeException" behavior would
     * undo that protective revocation right before it could take effect,
     * silently defeating the theft mitigation.
     *
     * @throws InvalidRefreshTokenException if the token is unknown, expired,
     *                                       or already revoked (reuse)
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public RotationResult rotate(String rawToken) {
        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (existing.isRevoked()) {
            // Token was already rotated (or logged out) — this presentation is a
            // replay. Assume compromise and kill every active session for the user.
            revokeAllActiveTokensForUser(existing.getUser().getId());
            throw new InvalidRefreshTokenException();
        }

        if (existing.isExpired()) {
            throw new InvalidRefreshTokenException();
        }

        User user = existing.getUser();
        UUID userId = user.getId();
        Role role = user.getRole();

        String newRawToken = generateRawToken();
        Instant expiresAt = Instant.now().plus(properties.getTtl());
        RefreshToken replacement = RefreshToken.issue(user, hash(newRawToken), expiresAt);
        refreshTokenRepository.save(replacement);

        existing.revokeAndReplaceWith(replacement.getId());
        refreshTokenRepository.save(existing);

        // Return plain userId/role rather than the User proxy: this method's
        // transaction (and Hibernate session) closes when it returns, and the
        // caller (AuthService.refresh) is deliberately non-transactional —
        // touching a lazy field on the User proxy afterwards would throw
        // LazyInitializationException.
        return new RotationResult(userId, role, newRawToken, expiresAt);
    }

    /** Revokes the specific session token presented at logout. */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .filter(token -> !token.isRevoked())
                .ifPresent(RefreshToken::revoke);
    }

    @Transactional
    public void revokeAllActiveTokensForUser(UUID userId) {
        List<RefreshToken> active = refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId);
        active.forEach(RefreshToken::revoke);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record IssuedRefreshToken(String rawToken, Instant expiresAt) {
    }

    public record RotationResult(UUID userId, Role role, String rawToken, Instant expiresAt) {
    }
}
