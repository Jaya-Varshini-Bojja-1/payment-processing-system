package com.portfolio.paymentsystem.gateway;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * V1's only {@link SimulatedPaymentGateway} adapter. Deterministic (never
 * {@code Math.random()}/{@code ThreadLocalRandom}) so the exact same
 * request always produces the exact same outcome: automated tests are
 * never flaky, and a demo/Postman script can reliably exercise success,
 * retry-then-succeed, retry-then-exhaust, and immediate-decline paths just
 * by choosing an amount. See {@link GatewayProperties} for the bucket
 * boundaries.
 */
@Component
public class InMemorySimulatedPaymentGateway implements SimulatedPaymentGateway {

    private final GatewayProperties properties;

    public InMemorySimulatedPaymentGateway(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public GatewayResult process(GatewayRequest request) {
        int bucket = (int) (request.amountPaise() % 100);

        if (bucket < properties.getSuccessUpperBound()) {
            return succeed();
        }
        if (bucket < properties.getRetrySucceedsUpperBound()) {
            // Only the very first attempt fails -- everything from the first
            // retry onward succeeds, so this bucket always resolves without
            // ever exhausting PaymentProperties#maxGatewayAttempts.
            if (request.attemptNumber() == 0) {
                return GatewayResult.retryableFailure(
                        "Simulated transient gateway failure (amount bucket " + bucket + "), attempt " + request.attemptNumber());
            }
            return succeed();
        }
        if (bucket < properties.getNonRetryableUpperBound()) {
            // Fails on every attempt -- the intended path to REQUIRES_MANUAL_REVIEW once retries exhaust.
            return GatewayResult.retryableFailure(
                    "Simulated transient gateway failure (amount bucket " + bucket + "), attempt " + request.attemptNumber());
        }
        return GatewayResult.nonRetryableFailure("Simulated non-retryable gateway decline (amount bucket " + bucket + ")");
    }

    private GatewayResult succeed() {
        return GatewayResult.success("SIM-" + UUID.randomUUID());
    }
}
