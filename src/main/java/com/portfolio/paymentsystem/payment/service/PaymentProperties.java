package com.portfolio.paymentsystem.payment.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway-attempt policy for M5 — a separate axis from the outbox's own
 * publish-retry/backoff ({@code OutboxProperties}): this governs how many
 * times {@code PaymentOutcomeService} is willing to re-dispatch a
 * {@code PaymentTransaction} to the (simulated) gateway itself after a
 * retryable failure, not how many times the outbox retries a Kafka send.
 */
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {

    /** Total gateway attempts (including the first) before a payment moves to REQUIRES_MANUAL_REVIEW. */
    private int maxGatewayAttempts = 3;

    /** Delay before the first retry; doubled (see {@link #retryBackoffMultiplier}) on each subsequent one. */
    private Duration retryInitialBackoff = Duration.ofSeconds(2);

    private double retryBackoffMultiplier = 2.0;

    public int getMaxGatewayAttempts() {
        return maxGatewayAttempts;
    }

    public void setMaxGatewayAttempts(int maxGatewayAttempts) {
        this.maxGatewayAttempts = maxGatewayAttempts;
    }

    public Duration getRetryInitialBackoff() {
        return retryInitialBackoff;
    }

    public void setRetryInitialBackoff(Duration retryInitialBackoff) {
        this.retryInitialBackoff = retryInitialBackoff;
    }

    public double getRetryBackoffMultiplier() {
        return retryBackoffMultiplier;
    }

    public void setRetryBackoffMultiplier(double retryBackoffMultiplier) {
        this.retryBackoffMultiplier = retryBackoffMultiplier;
    }
}
