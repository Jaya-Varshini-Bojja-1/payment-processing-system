package com.portfolio.paymentsystem.payment.event;

import java.util.UUID;

/** Published once, when a payment moves to {@code REQUIRES_MANUAL_REVIEW} — ops visibility, not a retry signal. */
public record PaymentProcessingExhaustedPayload(
        UUID paymentTransactionId, String type, long amountPaise, int attemptCount, String failureReason) {
}
