package com.portfolio.paymentsystem.outbox.domain;

/**
 * Lifecycle of one {@link OutboxEvent} row.
 * <p>
 * {@code PUBLISHING} exists (beyond the {@code PENDING}/{@code PUBLISHED}/
 * {@code FAILED} states in the project plan) so a publisher that dies
 * between "sent to Kafka" and "marked PUBLISHED" leaves a visible trace:
 * such rows are reclaimed back to {@code PENDING} after a timeout instead
 * of being silently stuck forever. Reclaiming means the row is retried —
 * which can duplicate the Kafka send — but that duplication is exactly what
 * the consumer-side inbox exists to absorb.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    FAILED
}
