package com.portfolio.paymentsystem.outbox.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A durable "please publish this to Kafka" instruction, written by
 * {@code OutboxEventWriter} in the same database transaction as the domain
 * change it describes. {@link #payloadJson} already holds the complete
 * envelope (event id, type, aggregate, occurred-at, and the domain payload)
 * — the publisher sends it to Kafka byte-for-byte, without needing to know
 * what kind of event it is.
 * <p>
 * Implements {@link Persistable} for the same reason as {@code Transfer}
 * and {@code IdempotencyRecord}: the id is assigned in application code
 * (it doubles as the Kafka message's {@code eventId}, generated before the
 * row exists so the envelope can embed it), and this entity has no
 * {@code @Version} column. Without this, Spring Data's default "is this
 * new?" check would route the first {@code save()} through {@code merge()}
 * instead of {@code persist()}.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(nullable = false, updatable = false, length = 128)
    private String topic;

    @Column(name = "dlq_topic", nullable = false, updatable = false, length = 128)
    private String dlqTopic;

    @Column(name = "payload_json", nullable = false, updatable = false, columnDefinition = "text")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // JPA
    }

    /**
     * @param id must already equal the {@code eventId} embedded in
     *           {@code payloadJson} — the caller (an {@code OutboxEventWriter})
     *           generates it up front for exactly this reason.
     */
    public static OutboxEvent create(
            UUID id, String aggregateType, UUID aggregateId, String eventType,
            String topic, String dlqTopic, String payloadJson) {
        return create(id, aggregateType, aggregateId, eventType, topic, dlqTopic, payloadJson, Instant.now());
    }

    /**
     * As above, but the row is not due for publishing until {@code firstAttemptAt} —
     * used for a gateway-retry backoff window (see M5's
     * {@code PaymentEventPublisher}) rather than the immediate-dispatch default
     * every M4 call site still uses.
     */
    public static OutboxEvent create(
            UUID id, String aggregateType, UUID aggregateId, String eventType,
            String topic, String dlqTopic, String payloadJson, Instant firstAttemptAt) {
        OutboxEvent event = new OutboxEvent();
        event.id = id;
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.topic = topic;
        event.dlqTopic = dlqTopic;
        event.payloadJson = payloadJson;
        event.status = OutboxStatus.PENDING;
        event.attemptCount = 0;
        event.nextAttemptAt = firstAttemptAt;
        return event;
    }

    /**
     * Also stamps {@link #nextAttemptAt} with the claim time — while
     * {@code PUBLISHING}, that field means "claimed at," not "due at" (see
     * {@code OutboxEventRepository#lockDueForPublishing}), which is how a
     * publisher that dies before this row reaches a terminal state gets
     * reclaimed after the stale-publishing timeout.
     */
    public void markPublishing() {
        this.status = OutboxStatus.PUBLISHING;
        this.nextAttemptAt = Instant.now();
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    /** A send attempt failed but attempts remain: go back to PENDING, due again at {@code nextAttemptAt}. */
    public void scheduleRetry(String error, Instant nextAttemptAt) {
        this.status = OutboxStatus.PENDING;
        this.attemptCount += 1;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttemptAt;
    }

    /** Attempts are exhausted: terminal failure. The publisher is responsible for also notifying the DLQ topic. */
    public void markExhausted(String error) {
        this.status = OutboxStatus.FAILED;
        this.attemptCount += 1;
        this.lastError = truncate(error);
    }

    /** A prior publisher instance died mid-send; make this row claimable again without counting it as a failed attempt. */
    public void reclaimStalePublishing() {
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = Instant.now();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getDlqTopic() {
        return dlqTopic;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
