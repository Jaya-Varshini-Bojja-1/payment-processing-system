package com.portfolio.paymentsystem.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * These tests are the concrete proof of the M6 hardening rule: an
 * unreachable Redis must fail {@code isDenylisted} CLOSED (treat the token
 * as revoked) while {@code denylist} (the logout write) stays best-effort
 * and never throws — the opposite failure-mode philosophy from
 * {@code RateLimiterServiceTest}, deliberately (see class Javadoc on
 * {@code TokenDenylistService}).
 */
@ExtendWith(MockitoExtension.class)
class TokenDenylistServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private TokenDenylistService tokenDenylistService;

    private TokenDenylistService service() {
        return new TokenDenylistService(redisTemplate);
    }

    @Test
    void isDenylistedReturnsTrueWhenTheKeyExists() {
        tokenDenylistService = service();
        when(redisTemplate.hasKey("jwt:denylist:jti-1")).thenReturn(true);

        assertThat(tokenDenylistService.isDenylisted("jti-1")).isTrue();
    }

    @Test
    void isDenylistedReturnsFalseWhenTheKeyIsAbsent() {
        tokenDenylistService = service();
        when(redisTemplate.hasKey("jwt:denylist:jti-1")).thenReturn(false);

        assertThat(tokenDenylistService.isDenylisted("jti-1")).isFalse();
    }

    /** The critical fail-CLOSED assertion: a Redis error must read as "revoked," never "valid." */
    @Test
    void isDenylistedFailsClosedWhenRedisErrors() {
        tokenDenylistService = service();
        when(redisTemplate.hasKey(any())).thenThrow(new RuntimeException("Redis connection refused"));

        assertThat(tokenDenylistService.isDenylisted("jti-1")).isTrue();
    }

    @Test
    void denylistWritesWithTtlEqualToTheRemainingTokenLife() {
        tokenDenylistService = service();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Instant expiresAt = Instant.now().plusSeconds(120);

        tokenDenylistService.denylist("jti-1", expiresAt);

        verify(valueOperations).set(eq("jwt:denylist:jti-1"), eq("revoked"), org.mockito.ArgumentMatchers.argThat(
                (Duration ttl) -> ttl.toSeconds() > 115 && ttl.toSeconds() <= 120));
    }

    @Test
    void denylistIsANoOpForAnAlreadyExpiredToken() {
        tokenDenylistService = service();

        tokenDenylistService.denylist("jti-1", Instant.now().minusSeconds(5));

        org.mockito.Mockito.verifyNoInteractions(redisTemplate);
    }

    /** The best-effort write assertion: a Redis error on logout must never propagate. */
    @Test
    void denylistSwallowsARedisWriteFailureWithoutThrowing() {
        tokenDenylistService = service();
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        assertThatCode(() -> tokenDenylistService.denylist("jti-1", Instant.now().plusSeconds(60)))
                .doesNotThrowAnyException();
    }
}
