package com.portfolio.paymentsystem.payment.event;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.portfolio.paymentsystem.outbox.service.OutboxEventWriter;
import com.portfolio.paymentsystem.outbox.service.OutboxProperties;
import com.portfolio.paymentsystem.payment.domain.PaymentTransaction;
import com.portfolio.paymentsystem.payment.service.PaymentProperties;

/**
 * The payment module's only touchpoint with the outbox — mirrors
 * {@code transfer.event.TransferEventPublisher}, with one structural
 * difference driven by M5's retry design: a {@code Transfer} publishes
 * exactly one {@code TransferCompleted} event ever, but the SAME
 * {@code PaymentTransaction} legitimately gets a fresh outbox row every
 * time it is (re)dispatched to the gateway (initial attempt, then one row
 * per retry). Since {@code outbox_events} enforces
 * {@code UNIQUE(aggregate_type, aggregate_id, event_type)} (see the V5
 * migration) as a double-post guard, reusing the literal event type
 * {@code "PaymentProcessingRequested"} for every retry would collide on the
 * second attempt. Suffixing the event type with the attempt number turns
 * every dispatch into a genuinely distinct fact ("please try attempt N"),
 * which is both true and enough to satisfy the constraint without touching
 * the generic outbox schema at all.
 */
@Service
public class PaymentEventPublisher {

    static final String AGGREGATE_TYPE = "PaymentTransaction";
    static final String PROCESSING_REQUESTED_EVENT_TYPE_PREFIX = "PaymentProcessingRequested:";
    static final String EXHAUSTED_EVENT_TYPE = "PaymentProcessingExhausted";

    private final OutboxEventWriter outboxEventWriter;
    private final OutboxProperties outboxProperties;
    private final PaymentProperties paymentProperties;

    public PaymentEventPublisher(
            OutboxEventWriter outboxEventWriter, OutboxProperties outboxProperties, PaymentProperties paymentProperties) {
        this.outboxEventWriter = outboxEventWriter;
        this.outboxProperties = outboxProperties;
        this.paymentProperties = paymentProperties;
    }

    /** First dispatch, right after {@code PaymentTransaction} creation. Always due immediately. */
    public void publishProcessingRequested(PaymentTransaction payment) {
        append(payment, Duration.ZERO);
    }

    /**
     * Re-dispatch after a retryable gateway failure. {@code payment.getAttemptCount()}
     * must already reflect this retry (i.e. called after
     * {@code PaymentTransaction#scheduleRetry}) so both the event type
     * suffix and the backoff window are computed from the attempt about to
     * be made, not the one that just failed.
     */
    public void publishRetry(PaymentTransaction payment) {
        append(payment, backoffFor(payment.getAttemptCount()));
    }

    private void append(PaymentTransaction payment, Duration delay) {
        PaymentProcessingRequestedPayload payload = new PaymentProcessingRequestedPayload(
                payment.getId(), payment.getType().name(), payment.getAmountPaise(), payment.getAttemptCount());
        String eventType = PROCESSING_REQUESTED_EVENT_TYPE_PREFIX + payment.getAttemptCount();
        outboxEventWriter.append(
                AGGREGATE_TYPE, payment.getId(), eventType,
                outboxProperties.getPaymentProcessingTopic(), outboxProperties.getPaymentDlqTopic(), payload, delay);
    }

    /**
     * Routed through the outbox rather than a raw {@code KafkaTemplate.send}
     * so the "this payment needs manual review" notification is exactly as
     * durable as the money-moving events: if Kafka is unreachable at the
     * moment attempts are exhausted, this row still commits with the
     * {@code REQUIRES_MANUAL_REVIEW} status change and {@code OutboxPublisher}
     * retries delivering it like any other row, instead of the notification
     * being silently lost.
     */
    public void publishExhausted(PaymentTransaction payment) {
        PaymentProcessingExhaustedPayload payload = new PaymentProcessingExhaustedPayload(
                payment.getId(), payment.getType().name(), payment.getAmountPaise(),
                payment.getAttemptCount(), payment.getFailureReason());
        outboxEventWriter.append(
                AGGREGATE_TYPE, payment.getId(), EXHAUSTED_EVENT_TYPE,
                outboxProperties.getPaymentDlqTopic(), outboxProperties.getPaymentDlqTopic(), payload);
    }

    private Duration backoffFor(int attemptCountAfterRetryScheduled) {
        double factor = Math.pow(paymentProperties.getRetryBackoffMultiplier(), Math.max(attemptCountAfterRetryScheduled - 1, 0));
        return Duration.ofMillis((long) (paymentProperties.getRetryInitialBackoff().toMillis() * factor));
    }
}
