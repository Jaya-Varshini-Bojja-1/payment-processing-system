package com.portfolio.paymentsystem.common.idempotency.domain;

/**
 * Lifecycle of one client {@code Idempotency-Key} claim.
 * <ul>
 *   <li>{@code IN_PROGRESS} — claimed, business logic is running. A second
 *       request with the same key observed in this state means a genuine
 *       concurrent duplicate is in flight right now.</li>
 *   <li>{@code COMPLETED} — the operation succeeded; {@code responseBody}/
 *       {@code httpStatus} hold the exact response to replay.</li>
 *   <li>{@code FAILED} — the operation reached a deterministic business
 *       failure (e.g. insufficient balance); also replayed verbatim, so a
 *       retry never re-runs logic that already produced a definitive
 *       answer.</li>
 * </ul>
 * There is deliberately no {@code EXPIRED}/timeout state in M3: unexpected
 * errors and exhausted concurrency retries instead delete the row (see
 * {@code IdempotencyService#release}) so the key becomes usable again
 * immediately, rather than needing a background reaper.
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
