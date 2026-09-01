package com.portfolio.paymentsystem.outbox.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentsystem.outbox.domain.OutboxEvent;
import com.portfolio.paymentsystem.outbox.event.OutboxEventEnvelope;
import com.portfolio.paymentsystem.outbox.exception.DuplicateOutboxEventException;
import com.portfolio.paymentsystem.outbox.repository.OutboxEventRepository;

/**
 * The only way a durable "please publish this event" row gets created.
 * <p>
 * Deliberately has no {@code @Transactional} of its own: callers (e.g.
 * {@code TransferEventPublisher}, invoked from {@code TransferExecutionService})
 * must call this from <b>inside</b> their own existing business transaction.
 * That is what makes this the fix for the dual-write problem — the outbox
 * row commits or rolls back atomically with the domain change it describes,
 * because it is written on the very same connection/transaction, not a
 * separate one.
 * <p>
 * Flushes immediately (like {@code LedgerService#saveTransactionHeader})
 * purely so a {@code UNIQUE(aggregate_type, aggregate_id, event_type)}
 * violation surfaces here as a clear exception instead of an opaque
 * constraint failure at commit time.
 */
@Service
public class OutboxEventWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @return the generated {@code eventId} (equal to the outbox row's id and
     *         the envelope's {@code eventId}), in case the caller wants to log it.
     */
    public UUID append(
            String aggregateType, UUID aggregateId, String eventType,
            String topic, String dlqTopic, Object payload) {
        return append(aggregateType, aggregateId, eventType, topic, dlqTopic, payload, Duration.ZERO);
    }

    /**
     * As above, but the row is not claimable by {@code OutboxPublisher} until
     * {@code initialDelay} has elapsed — M5's gateway-retry backoff (see
     * {@code PaymentEventPublisher}) uses this so a retryable failure doesn't
     * hammer the gateway again immediately. {@code occurredAt} in the
     * envelope is still "now" regardless of the delay: that field means
     * "when this event happened," not "when it's due."
     */
    public UUID append(
            String aggregateType, UUID aggregateId, String eventType,
            String topic, String dlqTopic, Object payload, Duration initialDelay) {
        UUID eventId = UUID.randomUUID();
        OutboxEventEnvelope<Object> envelope = new OutboxEventEnvelope<>(
                eventId, eventType, aggregateType, aggregateId, Instant.now(), payload);

        OutboxEvent event = OutboxEvent.create(
                eventId, aggregateType, aggregateId, eventType, topic, dlqTopic, writeJson(envelope),
                Instant.now().plus(initialDelay));

        try {
            outboxEventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateOutboxEventException(aggregateType, aggregateId, eventType);
        }
        return eventId;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }
    }
}
