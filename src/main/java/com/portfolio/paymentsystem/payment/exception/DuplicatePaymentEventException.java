package com.portfolio.paymentsystem.payment.exception;

import java.util.UUID;

/**
 * Thrown by {@code PaymentOutcomeService#applyOutcome} when the inbox insert
 * that closes its transaction hits {@code inbox_events}'s primary key — this
 * exact Kafka message (by {@code eventId}) was already durably processed by
 * an earlier delivery. A plain {@link RuntimeException}, not an
 * {@code ApiException}: this is an internal control-flow signal consumed by
 * {@code PaymentProcessingConsumer} (which acks and moves on), never
 * surfaced to an HTTP client.
 */
public class DuplicatePaymentEventException extends RuntimeException {

    public DuplicatePaymentEventException(UUID eventId) {
        super("Payment processing event '" + eventId + "' was already applied");
    }
}
