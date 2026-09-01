package com.portfolio.paymentsystem.transfer.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.portfolio.paymentsystem.outbox.event.OutboxEventEnvelope;
import com.portfolio.paymentsystem.outbox.event.OutboxEventEnvelopeReader;
import com.portfolio.paymentsystem.outbox.service.InboxEventGuard;

/**
 * The consumer half of M4's one event type. Everything money-related about
 * this transfer already happened, synchronously, before this message was
 * even written to the outbox (see {@code TransferExecutionService}) — this
 * listener's job is only to demonstrate (and prove, in tests) idempotent
 * <i>reaction</i> to an at-least-once event, not to move money again.
 * <p>
 * Manual acknowledgment (see {@code spring.kafka.listener.ack-mode} in
 * application.yml) is what ties this to the crash-recovery story: the
 * offset only advances after {@link InboxEventGuard#recordIfNew} has
 * durably committed. If this process crashes between consuming and
 * acking, Kafka redelivers the same message, and the inbox row already
 * being there is exactly what makes that redelivery a safe no-op.
 */
@Component
public class TransferCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(TransferCompletedListener.class);

    private final OutboxEventEnvelopeReader envelopeReader;
    private final InboxEventGuard inboxEventGuard;

    public TransferCompletedListener(OutboxEventEnvelopeReader envelopeReader, InboxEventGuard inboxEventGuard) {
        this.envelopeReader = envelopeReader;
        this.inboxEventGuard = inboxEventGuard;
    }

    @KafkaListener(topics = "${app.outbox.transfer-completed-topic}")
    public void onTransferCompleted(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        // Left unhandled on purpose: a malformed envelope throws
        // IllegalArgumentException here, which Spring's error handler
        // treats as a poison message — retry, then CONSUME_FAILED DLQ (see
        // ConsumeFailureDlqPublisher) — rather than this method silently
        // acking a message it never understood.
        OutboxEventEnvelope<TransferCompletedPayload> envelope = envelopeReader.read(record.value(), TransferCompletedPayload.class);

        boolean isNewEvent = inboxEventGuard.recordIfNew(envelope.eventId(), envelope.eventType(), envelope.aggregateId());
        if (!isNewEvent) {
            log.info("Skipping duplicate TransferCompleted delivery eventId={} transferId={} (already recorded in inbox)",
                    envelope.eventId(), envelope.aggregateId());
            acknowledgment.acknowledge();
            return;
        }

        TransferCompletedPayload payload = envelope.payload();
        log.info("Consumed TransferCompleted transferId={} sourceAccountId={} destinationAccountId={} amountPaise={} eventId={}",
                payload.transferId(), payload.sourceAccountId(), payload.destinationAccountId(),
                payload.amountPaise(), envelope.eventId());

        acknowledgment.acknowledge();
    }
}
