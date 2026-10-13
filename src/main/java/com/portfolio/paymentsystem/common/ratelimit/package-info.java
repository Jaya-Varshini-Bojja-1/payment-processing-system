/**
 * M6: per-user Redis rate limiting for money-moving POSTs (see
 * PROJECT_PLAN.md section 12 and section 18's M6 row).
 * <p>
 * Deliberately fails OPEN on Redis errors — see {@code RateLimiterService}
 * for why that is a different (and correct) tradeoff from
 * {@code com.portfolio.paymentsystem.identity.security.TokenDenylistService},
 * which fails CLOSED. Rate limiting is abuse-prevention on top of a system
 * whose financial correctness is already guaranteed by PostgreSQL
 * (idempotency records, ledger, optimistic locking); an unreachable Redis
 * must not turn an optional throttle into an outage for legitimate traffic.
 */
package com.portfolio.paymentsystem.common.ratelimit;
