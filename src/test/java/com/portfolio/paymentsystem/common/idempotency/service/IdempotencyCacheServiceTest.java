package com.portfolio.paymentsystem.common.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentsystem.common.idempotency.service.IdempotencyCacheService.CachedOutcome;

/**
 * These tests are the concrete proof of the "Redis failure never blocks
 * money movement" rule: every failure path here must degrade to "treat as
 * a miss," never to a thrown exception the caller would have to handle.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyCacheServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotencyCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new IdempotencyCacheService(redisTemplate, new ObjectMapper(), new IdempotencyProperties());
    }

    @Test
    void returnsEmptyOnCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(null);

        assertThat(cacheService.get("TRANSFER", USER_ID, "key-1", "hash-1")).isEmpty();
    }

    @Test
    void returnsTheCachedOutcomeWhenTheRequestHashMatches() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any()))
                .thenReturn("{\"requestHash\":\"hash-1\",\"httpStatus\":201,\"responseBody\":\"{\\\"id\\\":\\\"t1\\\"}\"}");

        Optional<CachedOutcome> result = cacheService.get("TRANSFER", USER_ID, "key-1", "hash-1");

        assertThat(result).isPresent();
        assertThat(result.get().httpStatus()).isEqualTo(201);
        assertThat(result.get().responseBody()).isEqualTo("{\"id\":\"t1\"}");
    }

    /**
     * A hash mismatch must NOT be treated as "reject here" — only
     * PostgreSQL's authoritative check produces the 422. The cache just
     * refuses to serve a stale/wrong answer and lets the real check run.
     */
    @Test
    void treatsARequestHashMismatchAsAMissRatherThanRejecting() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any()))
                .thenReturn("{\"requestHash\":\"different-hash\",\"httpStatus\":201,\"responseBody\":\"{}\"}");

        assertThat(cacheService.get("TRANSFER", USER_ID, "key-1", "hash-1")).isEmpty();
    }

    @Test
    void treatsARedisReadFailureAsAMiss() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        assertThat(cacheService.get("TRANSFER", USER_ID, "key-1", "hash-1")).isEmpty();
    }

    @Test
    void putSwallowsARedisWriteFailureWithoutThrowing() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        assertThatCode(() -> cacheService.put("TRANSFER", USER_ID, "key-1", new CachedOutcome("hash-1", 201, "{}")))
                .doesNotThrowAnyException();
    }

    @Test
    void putWritesTheSerializedOutcomeWithTheConfiguredTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        cacheService.put("TRANSFER", USER_ID, "key-1", new CachedOutcome("hash-1", 201, "{}"));

        verify(valueOperations).set(
                eq("idempotency:TRANSFER:" + USER_ID + ":key-1"),
                anyString(),
                eq(Duration.ofHours(24)));
    }
}
