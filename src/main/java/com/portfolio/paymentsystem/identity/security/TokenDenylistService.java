package com.portfolio.paymentsystem.identity.security;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed denylist for access-token {@code jti}s revoked by logout.
 * <p>
 * Entries are stored with a TTL equal to the token's remaining lifetime, so
 * the denylist self-cleans and never grows beyond "currently-issued and
 * not-yet-expired tokens that were explicitly revoked."
 *
 * <h2>M6 hardening: reads fail CLOSED, writes are best-effort</h2>
 * {@link #isDenylisted} is consulted on every authenticated request
 * ({@code JwtAuthenticationFilter}) to decide whether a token may be
 * trusted. If Redis cannot be reached, we cannot prove the token is
 * <b>not</b> revoked — so per this project's security policy, the check
 * fails CLOSED: treat the token as revoked. In practice this means a Redis
 * outage takes every authenticated endpoint down with it (the caller sees
 * a normal {@code 401 UNAUTHENTICATED} from the existing
 * {@code JwtAuthenticationEntryPoint}, never a raw 500), which is the
 * accepted tradeoff — a revoked token must never slip through.
 * <p>
 * {@link #denylist} (the write, on logout) stays best-effort: a write
 * failure there must not roll back {@code AuthService.logout}'s refresh-
 * token revocation. The access token simply cannot be immediately revoked
 * until it expires naturally, bounded by its short TTL.
 */
@Service
public class TokenDenylistService {

    private static final Logger log = LoggerFactory.getLogger(TokenDenylistService.class);
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
        try {
            redisTemplate.opsForValue().set(key(jti), "revoked", ttl);
        } catch (Exception e) {
            log.warn("Denylist write failed (Redis unavailable) for jti={}; refresh-token revocation "
                    + "still applies, but this access token cannot be immediately revoked until it "
                    + "expires naturally", jti, e);
        }
    }

    public boolean isDenylisted(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(jti)));
        } catch (Exception e) {
            log.error("Denylist check failed (Redis unavailable) for jti={}; failing CLOSED — "
                    + "treating token as revoked until Redis recovers", jti, e);
            return true;
        }
    }

    private String key(String jti) {
        return KEY_PREFIX + jti;
    }
}
