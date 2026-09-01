package com.portfolio.paymentsystem.outbox.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * The consumer-side mirror of {@link OutboxEvent}: recording one here
 * (keyed by the producer-assigned {@code eventId}, never generated here) is
 * what makes a {@code @KafkaListener} idempotent under Kafka's
 * at-least-once delivery. See {@code InboxEventGuard} for the actual
 * "insert or detect duplicate" logic — this class is deliberately just
 * data.
 */
@Entity
@Table(name = "inbox_events")
public class InboxEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Transient
    private boolean isNew = true;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @CreationTimestamp
    @Column(name = "consumed_at", nullable = false, updatable = false)
    private Instant consumedAt;

    protected InboxEvent() {
        // JPA
    }

    public static InboxEvent record(UUID eventId, String eventType, UUID aggregateId) {
        InboxEvent event = new InboxEvent();
        event.eventId = eventId;
        event.eventType = eventType;
        event.aggregateId = aggregateId;
        return event;
    }

    @Override
    public UUID getId() {
        return eventId;
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

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }
}
