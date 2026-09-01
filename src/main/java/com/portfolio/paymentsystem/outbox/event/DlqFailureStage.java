package com.portfolio.paymentsystem.outbox.event;

/**
 * Where in the pipeline a message ended up on a DLQ topic. The two stages
 * share a topic in V1 (see {@code OutboxProperties}), but they are never
 * the same failure and must not be handled as if they were:
 * <ul>
 *   <li>{@link #PUBLISH_FAILED} — the event was <b>never confirmed
 *       delivered to Kafka</b>. {@code OutboxPublisher} exhausted its retry
 *       attempts trying to send it. The row is still sitting in Postgres
 *       ({@code outbox_events.status = FAILED}) as the durable record; no
 *       consumer has ever seen this event. Replaying it means re-attempting
 *       the Kafka send.</li>
 *   <li>{@link #CONSUME_FAILED} — the event <b>was</b> delivered to Kafka
 *       (at least once) and a consumer received it, but processing it kept
 *       throwing. Replaying it means re-attempting consumption, not
 *       re-publishing — the message already exists on the source topic.</li>
 * </ul>
 */
public enum DlqFailureStage {
    PUBLISH_FAILED,
    CONSUME_FAILED
}
