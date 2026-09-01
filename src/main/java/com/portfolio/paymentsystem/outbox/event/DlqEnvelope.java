package com.portfolio.paymentsystem.outbox.event;

import java.time.Instant;
import java.util.UUID;

/**
 * What actually gets sent to a {@code *.dlq} topic. Wraps (rather than
 * discards) the original {@link OutboxEventEnvelope} JSON so nothing is
 * lost — an operator reading a DLQ message can see exactly what event
 * failed, at which stage, why, and how many times it was attempted.
 *
 * @param originalPayloadJson the exact, untouched {@link OutboxEventEnvelope}
 *                            JSON that failed — not re-serialized, so it is
 *                            byte-for-byte what was (or would have been)
 *                            sent to the original topic.
 */
public record DlqEnvelope(
        DlqFailureStage failureStage,
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String originalTopic,
        int attemptCount,
        String reason,
        Instant failedAt,
        String originalPayloadJson
) {
}
