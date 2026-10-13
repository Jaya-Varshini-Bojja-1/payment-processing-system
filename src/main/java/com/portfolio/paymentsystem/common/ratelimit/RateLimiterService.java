package com.portfolio.paymentsystem.common.ratelimit;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Per-user, per-{@link RateLimitScope} fixed-window rate limiter backed by
 * Redis.
 * <p>
 * The increment, the conditional TTL set, and the remaining-TTL read all
 * happen inside a single Lua script executed atomically by the Redis
 * server itself — one network round trip, no client-side read-then-write
 * race. That atomicity is exactly what a lost update between two
 * concurrent requests near the limit would otherwise exploit (see
 * {@code RateLimiterConcurrencyIntegrationTest}).
 *
 * <h2>Why this fails OPEN (unlike {@code TokenDenylistService})</h2>
 * Rate limiting here is abuse-prevention layered on top of a system whose
 * financial correctness is already guaranteed by PostgreSQL — idempotency
 * records, the ledger, and optimistic locking do not depend on Redis at
 * all. So if Redis errors on this call, every code path below logs a
 * warning and lets the request through rather than turning an optional
 * component's outage into a full outage of legitimate traffic. This is a
 * deliberately different tradeoff than the JWT denylist, which fails
 * CLOSED because it guards authentication, not throughput.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);
    private static final String KEY_PREFIX = "ratelimit:";

    /**
     * KEYS[1] = the per-(scope,user) counter key; ARGV[1] = window length in
     * seconds. Returns {@code [currentCount, remainingTtlSeconds]}. The TTL
     * is only set on the request that creates the key (current == 1), so a
     * key's lifetime always matches the window it started, never resets on
     * every increment.
     */
    private static final String SCRIPT_BODY = """
            local current = redis.call('INCR', KEYS[1])
            if tonumber(current) == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('TTL', KEYS[1])
            return {current, ttl}
            """;

    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>(SCRIPT_BODY, List.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    public RateLimiterService(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public RateLimitResult tryConsume(RateLimitScope scope, UUID userId) {
        if (!properties.isEnabled()) {
            return RateLimitResult.allow();
        }
        try {
            List<?> result = redisTemplate.execute(
                    SCRIPT,
                    List.of(key(scope, userId)),
                    String.valueOf(properties.getWindow().toSeconds()));
            long current = ((Number) result.get(0)).longValue();
            long ttl = ((Number) result.get(1)).longValue();
            if (current <= properties.getLimit()) {
                return RateLimitResult.allow();
            }
            return RateLimitResult.deny(Math.max(ttl, 0));
        } catch (Exception e) {
            log.warn("Rate limit check failed (Redis unavailable) for scope={} userId={}; failing OPEN",
                    scope, userId, e);
            return RateLimitResult.allow();
        }
    }

    private String key(RateLimitScope scope, UUID userId) {
        return KEY_PREFIX + scope.name() + ":" + userId;
    }
}
