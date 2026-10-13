package com.portfolio.paymentsystem.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * These tests are the concrete proof of the "Redis failure never blocks
 * legitimate traffic" rule for the rate limiter — the opposite tradeoff
 * from {@code TokenDenylistServiceTest}, and deliberately so (see
 * {@link RateLimiterService}'s class Javadoc).
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private StringRedisTemplate redisTemplate;

    private RateLimitProperties properties;
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setLimit(5);
        properties.setWindow(Duration.ofSeconds(60));
        rateLimiterService = new RateLimiterService(redisTemplate, properties);
    }

    @Test
    void allowsWhenTheCountIsAtOrBelowTheLimit() {
        stubScriptResult(5L, 55L);

        RateLimitResult result = rateLimiterService.tryConsume(RateLimitScope.TRANSFER_CREATE, USER_ID);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void deniesWithRetryAfterFromTheScriptTtlWhenOverTheLimit() {
        stubScriptResult(6L, 40L);

        RateLimitResult result = rateLimiterService.tryConsume(RateLimitScope.TRANSFER_CREATE, USER_ID);

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(40L);
    }

    @Test
    void treatsARedisFailureAsAllowedRatherThanThrowing() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenThrow(new RuntimeException("Redis connection refused"));

        RateLimitResult result = rateLimiterService.tryConsume(RateLimitScope.TRANSFER_CREATE, USER_ID);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void doesNotCallRedisWhenDisabled() {
        properties.setEnabled(false);

        RateLimitResult result = rateLimiterService.tryConsume(RateLimitScope.TRANSFER_CREATE, USER_ID);

        assertThat(result.allowed()).isTrue();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void keysAreIsolatedPerScopeAndUser() {
        stubScriptResult(1L, 60L);

        rateLimiterService.tryConsume(RateLimitScope.TRANSFER_CREATE, USER_ID);
        rateLimiterService.tryConsume(RateLimitScope.PAYMENT_DEPOSIT, USER_ID);

        org.mockito.ArgumentCaptor<List> keysCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.times(2))
                .execute(any(RedisScript.class), keysCaptor.capture(), any());
        List<List> capturedKeys = keysCaptor.getAllValues();
        assertThat(capturedKeys.get(0)).containsExactly("ratelimit:TRANSFER_CREATE:" + USER_ID);
        assertThat(capturedKeys.get(1)).containsExactly("ratelimit:PAYMENT_DEPOSIT:" + USER_ID);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubScriptResult(long current, long ttl) {
        when(redisTemplate.<List>execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(List.of(current, ttl));
    }
}
