package com.portfolio.paymentsystem.payment.domain;

/**
 * {@code PENDING -> PROCESSING -> SUCCEEDED | FAILED | RETRYING -> ...}
 * terminating in {@code SUCCEEDED}, {@code FAILED}, or
 * {@code REQUIRES_MANUAL_REVIEW} (retries exhausted, pushed to the DLQ
 * topic for ops visibility) — see PROJECT_PLAN.md section 8. No ledger post
 * exists for a payment until it reaches {@code SUCCEEDED}: every other
 * terminal state means no money moved, so there is nothing to reverse.
 */
public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    RETRYING,
    REQUIRES_MANUAL_REVIEW;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == REQUIRES_MANUAL_REVIEW;
    }
}
