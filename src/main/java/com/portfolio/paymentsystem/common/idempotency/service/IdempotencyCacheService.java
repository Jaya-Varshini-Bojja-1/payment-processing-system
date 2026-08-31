package com.portfolio.paymentsystem.common.idempotency.service;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Redis <b>fast path</b> for idempotency lookups — a pure latency
 * optimization layered in front of {@link IdempotencyService}, never a
 * replacement for it.
 *
 * <h2>Why every method here swallows its own exceptions</h2>
 * Per {@code PROJECT_PLAN.md} section 10's hard rule, Redis being slow,
 * unreachable, or erroring must never change correctness — it must simply
 * behave like a cache miss (for reads) or a no-op (for writes), always
 * falling through to PostgreSQL. If this class let a Redis exception
 * propagate, an outage of a "purely optional" component would start
 * blocking money movement, which defeats the entire point of calling it
 * optional. Every public method here therefore has a single
 * {@code catch (Exception)} around its Redis call — deliberately broad,
 * because the failure mode we care about ("Redis is unavailable") can
 * surface as a connection exception, a timeout, a serialization exception,
 * or several other {@code RuntimeException} subtypes, and all of them must
 * be handled identically: log and fall through.
 */
@Service
public class IdempotencyCacheService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCacheService.class);
    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties properties;

    public IdempotencyCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, IdempotencyProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * A present result means "safe to replay" — the caller still supplies
     * {@code requestHash} and this method silently treats a mismatch as a
     * miss (falls through to PostgreSQL) rather than deciding "reject"
     * itself. Only PostgreSQL's {@code idempotency_records} row is ever
     * allowed to produce the definitive 422 for key reuse; the cache's job
     * is narrower.
     */
    public Optional<CachedOutcome> get(String resourceType, UUID userId, String idempotencyKey, String requestHash) {
        try {
            String json = redisTemplate.opsForValue().get(key(resourceType, userId, idempotencyKey));
            if (json == null) {
                return Optional.empty();
            }
            CachedOutcome cached = objectMapper.readValue(json, CachedOutcome.class);
            if (!cached.requestHash().equals(requestHash)) {
                return Optional.empty();
            }
            return Optional.of(cached);
        } catch (Exception e) {
            log.warn("Idempotency cache read failed for key='{}'; falling through to PostgreSQL", idempotencyKey, e);
            return Optional.empty();
        }
    }

    /**
     * Best-effort mirror, called only <b>after</b> the PostgreSQL
     * transaction that produced this outcome has already committed. A
     * failure here never rolls back or fails the caller's request — the
     * money movement (or business failure record) already happened safely.
     */
    public void put(String resourceType, UUID userId, String idempotencyKey, CachedOutcome outcome) {
        try {
            String json = objectMapper.writeValueAsString(outcome);
            redisTemplate.opsForValue().set(key(resourceType, userId, idempotencyKey), json, properties.getRedisTtl());
        } catch (Exception e) {
            log.warn("Best-effort idempotency cache write failed for key='{}'; ignoring", idempotencyKey, e);
        }
    }

    private String key(String resourceType, UUID userId, String idempotencyKey) {
        return KEY_PREFIX + resourceType + ":" + userId + ":" + idempotencyKey;
    }

    public record CachedOutcome(String requestHash, int httpStatus, String responseBody) {
    }
}
