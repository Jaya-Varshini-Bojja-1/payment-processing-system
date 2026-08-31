package com.portfolio.paymentsystem.identity.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed denylist for access-token {@code jti}s revoked by logout.
 * <p>
 * Entries are stored with a TTL equal to the token's remaining lifetime, so
 * the denylist self-cleans and never grows beyond "currently-issued and
 * not-yet-expired tokens that were explicitly revoked."
 */
@Service
public class TokenDenylistService {

    private static final String KEY_PREFIX = "jwt:denylist:";

    private final StringRedisTemplate redisTemplate;

    public TokenDenylistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void denylist(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return; // already expired; nothing to protect against
        }
        redisTemplate.opsForValue().set(key(jti), "revoked", ttl);
    }

    public boolean isDenylisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(jti)));
    }

    private String key(String jti) {
        return KEY_PREFIX + jti;
    }
}
