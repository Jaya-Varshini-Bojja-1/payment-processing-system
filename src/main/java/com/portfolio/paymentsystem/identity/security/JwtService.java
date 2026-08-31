package com.portfolio.paymentsystem.identity.security;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.portfolio.paymentsystem.identity.domain.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and validates access-token JWTs.
 * <p>
 * Access tokens are stateless (no DB round-trip to verify), so a signature +
 * expiry check is normally sufficient. The one piece of server-side state we
 * keep is the {@code jti} denylist in Redis, consulted by
 * {@link JwtAuthenticationFilter} to support immediate logout revocation
 * within the token's short lifetime.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] keyBytes = Base64.getDecoder().decode(properties.getSecret());
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public GeneratedAccessToken generateAccessToken(UUID userId, Role role) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getAccessTokenTtl());
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .id(jti)
                .subject(userId.toString())
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("role", role.name())
                .signWith(signingKey)
                .compact();

        return new GeneratedAccessToken(token, jti, expiry);
    }

    /**
     * Parses and validates signature + expiry. Throws
     * {@code io.jsonwebtoken.JwtException} (including
     * {@code ExpiredJwtException}) on any failure — callers treat that as
     * "unauthenticated", never as a 500.
     */
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public record GeneratedAccessToken(String token, String jti, Instant expiresAt) {
    }
}
