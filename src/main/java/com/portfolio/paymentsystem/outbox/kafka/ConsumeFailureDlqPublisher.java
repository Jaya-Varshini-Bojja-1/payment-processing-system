package com.portfolio.paymentsystem.outbox.kafka;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentsystem.outbox.event.DlqEnvelope;
import com.portfolio.paymentsystem.outbox.event.DlqFailureStage;
import com.portfolio.paymentsystem.outbox.service.OutboxProperties;

/**
 * Invoked by {@code DefaultErrorHandler} once a {@code @KafkaListener} has
 * exhausted its retries on one message. This is a genuinely different
 * failure from a publisher exhaustion: the event <b>was</b> delivered to
 * Kafka — a consumer definitely saw it — but processing it kept throwing
 * (e.g. a poison/malformed payload, or a downstream error). Tagging the
 * outgoing message {@link DlqFailureStage#CONSUME_FAILED} (as opposed to
 * the publisher's {@link DlqFailureStage#PUBLISH_FAILED}) is what lets an
 * operator reading the shared DLQ topic tell the two apart without
 * guessing from context.
 * <p>
 * Not {@code DeadLetterPublishingRecoverer}: that class forwards the
 * original record more or less as-is plus exception headers. Building our
 * own {@link DlqEnvelope} here keeps the DLQ message shape identical
 * (structurally, not just "close enough") regardless of which stage
 * produced it.
 */
@Component
public class ConsumeFailureDlqPublisher implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(ConsumeFailureDlqPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxProperties outboxProperties;

    public ConsumeFailureDlqPublisher(
            KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, OutboxProperties outboxProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.outboxProperties = outboxProperties;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        String rawValue = record.value() == null ? null : record.value().toString();
        DlqEnvelope dlqEnvelope = buildEnvelope(record, exception, rawValue);
        String dlqTopic = resolveDlqTopic(record.topic());

        try {
            String dlqJson = objectMapper.writeValueAsString(dlqEnvelope);
            kafkaTemplate.send(dlqTopic, String.valueOf(record.key()), dlqJson)
                    .get(outboxProperties.getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            log.warn("Consumer exhausted retries for topic={} partition={} offset={}; forwarded to DLQ topic={} as CONSUME_FAILED",
                    record.topic(), record.partition(), record.offset(), dlqTopic);
        } catch (Exception dlqFailure) {
            // Best-effort, same reasoning as OutboxPublisher's DLQ publish:
            // if this also fails, the offset still advances (the container
            // considers the message "recovered" once the recoverer returns),
            // so this failure is only visible in logs. Acceptable for V1 —
            // the alternative (blocking the partition forever on a truly
            // poison message) is worse.
            log.error("Failed to publish CONSUME_FAILED record to DLQ topic={} for original topic={} offset={}",
                    dlqTopic, record.topic(), record.offset(), dlqFailure);
        }
    }

    private DlqEnvelope buildEnvelope(ConsumerRecord<?, ?> record, Exception exception, String rawValue) {
        UUID eventId = null;
        String eventType = null;
        String aggregateType = null;
        UUID aggregateId = null;

        if (rawValue != null) {
            try {
                JsonNode root = objectMapper.readTree(rawValue);
                eventId = uuidOrNull(root.path("eventId").asText(null));
                eventType = textOrNull(root.path("eventType"));
                aggregateType = textOrNull(root.path("aggregateType"));
                aggregateId = uuidOrNull(root.path("aggregateId").asText(null));
            } catch (Exception parseFailure) {
                // A malformed envelope is exactly the kind of thing this
                // recoverer must survive — fields simply stay null.
                log.debug("Could not parse failing record's payload as an outbox envelope; DLQ entry will have partial metadata", parseFailure);
            }
        }

        return new DlqEnvelope(
                DlqFailureStage.CONSUME_FAILED,
                eventId, eventType, aggregateType, aggregateId,
                record.topic(), deliveryAttempt(record), rootCauseMessage(exception), Instant.now(), rawValue);
    }

    private int deliveryAttempt(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);
        if (header == null || header.value() == null || header.value().length < 4) {
            return outboxProperties.getConsumerMaxAttempts();
        }
        return java.nio.ByteBuffer.wrap(header.value()).getInt();
    }

    private String resolveDlqTopic(String originalTopic) {
        if (originalTopic.equals(outboxProperties.getTransferCompletedTopic())) {
            return outboxProperties.getTransferDlqTopic();
        }
        // Fallback for any future topic this class doesn't explicitly know
        // about yet (e.g. an M5 event type) — keeps this recoverer generic
        // enough to not require a code change for every new topic.
        return originalTopic + ".dlq";
    }

    private String rootCauseMessage(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private UUID uuidOrNull(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
