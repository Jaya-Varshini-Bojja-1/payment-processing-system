package com.portfolio.paymentsystem.transfer.domain;

/**
 * M3's transfer path is fully synchronous: a {@link Transfer} row is only
 * ever inserted once its ledger post has already succeeded in the same
 * database transaction (see {@code TransferExecutionService}), so in
 * practice every persisted row goes straight to {@code COMPLETED} — there
 * is no observable {@code PENDING} window. The enum still models all three
 * states because {@code PROJECT_PLAN.md} defines this as the general
 * Transfer status machine, and a future milestone could introduce a path
 * where a transfer is persisted before it is known to succeed.
 */
public enum TransferStatus {
    PENDING,
    COMPLETED,
    FAILED
}
