package com.portfolio.paymentsystem.common.ratelimit;

/**
 * Identifies which money-moving operation a rate-limit counter belongs to.
 * Each scope gets its own Redis counter per user (see
 * {@link RateLimiterService}), so a burst against one money-moving
 * endpoint cannot consume another endpoint's budget — even though, per
 * {@link RateLimitProperties}, they currently all share the same numeric
 * limit/window configuration.
 */
public enum RateLimitScope {
    TRANSFER_CREATE,
    PAYMENT_DEPOSIT,
    PAYMENT_WITHDRAWAL
}
