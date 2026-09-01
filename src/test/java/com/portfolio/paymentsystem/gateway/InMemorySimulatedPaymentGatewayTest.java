package com.portfolio.paymentsystem.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class InMemorySimulatedPaymentGatewayTest {

    private final GatewayProperties properties = new GatewayProperties();
    private final InMemorySimulatedPaymentGateway gateway = new InMemorySimulatedPaymentGateway(properties);

    private GatewayRequest requestWithAmount(long amountPaise, int attemptNumber) {
        return new GatewayRequest(UUID.randomUUID(), amountPaise, attemptNumber);
    }

    @Test
    void bucketBelowSuccessUpperBoundSucceedsOnTheFirstAttempt() {
        GatewayResult result = gateway.process(requestWithAmount(50_000, 0));

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.SUCCESS);
        assertThat(result.gatewayReference()).isNotBlank();
    }

    @Test
    void sameAmountAlwaysProducesTheSameOutcomeRegardlessOfHowManyTimesItIsCalled() {
        GatewayResult first = gateway.process(requestWithAmount(40_099, 2));
        GatewayResult second = gateway.process(requestWithAmount(40_099, 2));

        assertThat(first.outcome()).isEqualTo(second.outcome());
    }

    @Test
    void retrySucceedsBucketFailsOnlyOnTheFirstAttemptThenSucceeds() {
        // 80 <= bucket < 90
        long amount = 100_085L;

        GatewayResult firstAttempt = gateway.process(requestWithAmount(amount, 0));
        GatewayResult secondAttempt = gateway.process(requestWithAmount(amount, 1));

        assertThat(firstAttempt.outcome()).isEqualTo(GatewayOutcome.RETRYABLE_FAILURE);
        assertThat(secondAttempt.outcome()).isEqualTo(GatewayOutcome.SUCCESS);
    }

    @Test
    void alwaysRetryableBucketFailsRetryablyOnEveryAttempt() {
        // 90 <= bucket < 95
        long amount = 100_092L;

        GatewayResult firstAttempt = gateway.process(requestWithAmount(amount, 0));
        GatewayResult thirdAttempt = gateway.process(requestWithAmount(amount, 2));

        assertThat(firstAttempt.outcome()).isEqualTo(GatewayOutcome.RETRYABLE_FAILURE);
        assertThat(thirdAttempt.outcome()).isEqualTo(GatewayOutcome.RETRYABLE_FAILURE);
    }

    @Test
    void nonRetryableBucketDeclinesOnEveryAttempt() {
        // 95 <= bucket < 100
        long amount = 100_097L;

        GatewayResult result = gateway.process(requestWithAmount(amount, 0));

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.NON_RETRYABLE_FAILURE);
        assertThat(result.failureReason()).isNotBlank();
    }
}
