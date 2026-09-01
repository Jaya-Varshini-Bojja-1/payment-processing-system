package com.portfolio.paymentsystem.transfer.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import com.portfolio.paymentsystem.outbox.event.OutboxEventEnvelope;
import com.portfolio.paymentsystem.outbox.event.OutboxEventEnvelopeReader;
import com.portfolio.paymentsystem.outbox.service.InboxEventGuard;

/**
 * Unit tests for the listener's idempotency branch, with envelope parsing
 * and the inbox both mocked — the real, at-least-once-over-a-real-broker
 * proof is {@code TransferCompletedConsumerKafkaIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class TransferCompletedListenerTest {

    @Mock
    private OutboxEventEnvelopeReader envelopeReader;
    @Mock
    private InboxEventGuard inboxEventGuard;
    @Mock
    private Acknowledgment acknowledgment;

    private TransferCompletedListener listener;

    @BeforeEach
    void setUp() {
        listener = new TransferCompletedListener(envelopeReader, inboxEventGuard);
    }

    private OutboxEventEnvelope<TransferCompletedPayload> sampleEnvelope(UUID eventId, UUID transferId) {
        TransferCompletedPayload payload = new TransferCompletedPayload(
                transferId, UUID.randomUUID(), UUID.randomUUID(), 40_000L, "INR");
        return new OutboxEventEnvelope<>(eventId, "TransferCompleted", "Transfer", transferId, Instant.now(), payload);
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("transfers.completed", 0, 0L, "key", value);
    }

    @Test
    void newEventIsProcessedAndAcknowledged() {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        OutboxEventEnvelope<TransferCompletedPayload> envelope = sampleEnvelope(eventId, transferId);
        String json = "{\"eventId\":\"" + eventId + "\"}";

        when(envelopeReader.read(json, TransferCompletedPayload.class)).thenReturn(envelope);
        when(inboxEventGuard.recordIfNew(eventId, "TransferCompleted", transferId)).thenReturn(true);

        listener.onTransferCompleted(record(json), acknowledgment);

        verify(inboxEventGuard).recordIfNew(eventId, "TransferCompleted", transferId);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void duplicateEventIsSkippedButStillAcknowledged() {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        OutboxEventEnvelope<TransferCompletedPayload> envelope = sampleEnvelope(eventId, transferId);
        String json = "{\"eventId\":\"" + eventId + "\"}";

        when(envelopeReader.read(json, TransferCompletedPayload.class)).thenReturn(envelope);
        when(inboxEventGuard.recordIfNew(eventId, "TransferCompleted", transferId)).thenReturn(false);

        listener.onTransferCompleted(record(json), acknowledgment);

        // Acknowledged either way: a duplicate is not a processing failure,
        // it is a correctly-handled redelivery — retrying it would achieve
        // nothing and would just spin the same message forever.
        verify(acknowledgment).acknowledge();
    }

    @Test
    void malformedEnvelopePropagatesInsteadOfBeingAcknowledged() {
        String badJson = "not json";
        when(envelopeReader.read(badJson, TransferCompletedPayload.class))
                .thenThrow(new IllegalArgumentException("Malformed outbox event envelope JSON"));

        assertThatThrownBy(() -> listener.onTransferCompleted(record(badJson), acknowledgment))
                .isInstanceOf(IllegalArgumentException.class);

        // Left for Spring's error handler / ConsumeFailureDlqPublisher to
        // decide the outcome (retry, then DLQ) — this method must not have
        // acknowledged a message it never understood.
        verify(acknowledgment, never()).acknowledge();
    }
}
