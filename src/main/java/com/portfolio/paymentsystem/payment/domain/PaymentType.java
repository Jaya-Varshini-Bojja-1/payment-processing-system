package com.portfolio.paymentsystem.payment.domain;

/**
 * Gateway-backed money movement only. Kept a distinct type from
 * {@code Transfer} per PROJECT_PLAN.md's naming rule: internal P2P is never
 * called a "payment" in code, tables, or logs.
 */
public enum PaymentType {
    DEPOSIT,
    WITHDRAWAL
}
