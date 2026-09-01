package com.portfolio.paymentsystem.payment.event;

import java.util.UUID;

/**
 * {@code attemptNumber} mirrors {@code PaymentTransaction.attemptCount} at
 * the moment this event was written (0 for the first dispatch) — the
 * consumer passes it straight through to {@code GatewayRequest} so the
 * (simulated) gateway can vary behavior by attempt if it ever needs to,
 * and so logs can tell attempts apart without a DB round-trip.
 */
public record PaymentProcessingRequestedPayload(UUID paymentTransactionId, String type, long amountPaise, int attemptNumber) {
}
