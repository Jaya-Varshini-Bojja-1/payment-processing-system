package com.portfolio.paymentsystem.outbox.event;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The consumer-side mirror of {@code OutboxEventWriter}: parses the exact
 * JSON shape that writer produces. Deliberately reads field-by-field via a
 * {@link JsonNode} rather than deserializing straight into
 * {@code OutboxEventEnvelope<T>} — Java generics are erased at runtime, so
 * Jackson cannot infer {@code T} from a bare {@code Class<OutboxEventEnvelope>}
 * without extra type-token machinery this project doesn't otherwise need.
 */
@Component
public class OutboxEventEnvelopeReader {

    private final ObjectMapper objectMapper;

    public OutboxEventEnvelopeReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @throws IllegalArgumentException if {@code json} isn't a well-formed
     *         envelope, or its {@code payload} doesn't match
     *         {@code payloadType} — callers let this propagate so Spring
     *         Kafka's error handler treats it as a poison message (retry,
     *         then DLQ), rather than silently dropping it.
     */
    public <T> OutboxEventEnvelope<T> read(String json, Class<T> payloadType) {
        try {
            JsonNode root = objectMapper.readTree(json);
            UUID eventId = UUID.fromString(root.get("eventId").asText());
            String eventType = root.get("eventType").asText();
            String aggregateType = root.get("aggregateType").asText();
            UUID aggregateId = UUID.fromString(root.get("aggregateId").asText());
            Instant occurredAt = Instant.parse(root.get("occurredAt").asText());
            T payload = objectMapper.treeToValue(root.get("payload"), payloadType);
            return new OutboxEventEnvelope<>(eventId, eventType, aggregateType, aggregateId, occurredAt, payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed outbox event envelope JSON", e);
        }
    }
}
