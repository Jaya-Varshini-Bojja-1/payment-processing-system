package com.portfolio.paymentsystem.common.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M6: one limit/window pair applied to every {@link RateLimitScope}. Each
 * scope still gets an isolated Redis counter per user (see
 * {@code RateLimiterService}), so this is a single tuning knob rather than
 * one shared budget split across the money-moving endpoints.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    /** Kill switch: when false, {@code RateLimiterService} always allows without touching Redis. */
    private boolean enabled = true;

    /** Max requests per (scope, user) within {@link #window}. */
    private int limit = 20;

    /** Fixed-window duration; the Redis counter's TTL. */
    private Duration window = Duration.ofMinutes(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }
}
