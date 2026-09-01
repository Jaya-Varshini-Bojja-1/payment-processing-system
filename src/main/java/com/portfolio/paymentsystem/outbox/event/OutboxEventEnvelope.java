package com.portfolio.paymentsystem.outbox.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The full shape written to {@code outbox_events.payload_json} and, later,
 * sent verbatim to Kafka by the publisher. Deliberately generic over the
 * domain payload so this type — and everything that reads/writes it — never
 * needs to change when M5 introduces a second event type.
 */
public record OutboxEventEnvelope<T>(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        T payload
) {
}
