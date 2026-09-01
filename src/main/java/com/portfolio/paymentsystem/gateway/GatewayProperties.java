package com.portfolio.paymentsystem.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Boundaries for {@link InMemorySimulatedPaymentGateway}'s deterministic
 * amount-based outcome selection. Bucketing {@code amountPaise % 100} into
 * four ranges gives a repeatable "test amount" convention (the same idea as
 * card-network test card numbers) without any random/flaky behavior in
 * tests or demos: the last two digits of an amount ARE the scenario.
 * <ul>
 *   <li>{@code [0, successUpperBound)} — succeeds immediately.</li>
 *   <li>{@code [successUpperBound, retrySucceedsUpperBound)} — fails
 *       retryably on the first attempt only, then succeeds from the next
 *       attempt onward. Demonstrates the retry-then-succeed path.</li>
 *   <li>{@code [retrySucceedsUpperBound, nonRetryableUpperBound)} — fails
 *       retryably on every attempt, regardless of attempt number.
 *       Demonstrates retries exhausting into {@code REQUIRES_MANUAL_REVIEW}.</li>
 *   <li>{@code [nonRetryableUpperBound, 100)} — a deterministic decline on
 *       every attempt. Demonstrates the immediate/permanent-failure path.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.gateway")
public class GatewayProperties {

    private int successUpperBound = 80;
    private int retrySucceedsUpperBound = 90;
    private int nonRetryableUpperBound = 95;

    public int getSuccessUpperBound() {
        return successUpperBound;
    }

    public void setSuccessUpperBound(int successUpperBound) {
        this.successUpperBound = successUpperBound;
    }

    public int getRetrySucceedsUpperBound() {
        return retrySucceedsUpperBound;
    }

    public void setRetrySucceedsUpperBound(int retrySucceedsUpperBound) {
        this.retrySucceedsUpperBound = retrySucceedsUpperBound;
    }

    public int getNonRetryableUpperBound() {
        return nonRetryableUpperBound;
    }

    public void setNonRetryableUpperBound(int nonRetryableUpperBound) {
        this.nonRetryableUpperBound = nonRetryableUpperBound;
    }
}
