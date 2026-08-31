/**
 * Client-facing {@code Idempotency-Key} handling, shared by any module that
 * moves money (Transfer in M3, PaymentTransaction in M5). PostgreSQL
 * ({@code idempotency_records}) is always authoritative; Redis is only ever
 * a best-effort fast-path cache in front of it — see {@code IdempotencyService}
 * and {@code IdempotencyCacheService}.
 */
package com.portfolio.paymentsystem.common.idempotency;
