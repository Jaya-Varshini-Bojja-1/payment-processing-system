package com.portfolio.paymentsystem.common.ratelimit;

/**
 * Outcome of one {@code RateLimiterService#tryConsume} call.
 * {@code retryAfterSeconds} is only meaningful when {@code allowed} is
 * {@code false} — it becomes the {@code Retry-After} header on the
 * resulting {@code 429}.
 */
public record RateLimitResult(boolean allowed, long retryAfterSeconds) {

    public static RateLimitResult allow() {
        return new RateLimitResult(true, 0);
    }

    public static RateLimitResult deny(long retryAfterSeconds) {
        return new RateLimitResult(false, retryAfterSeconds);
    }
}
