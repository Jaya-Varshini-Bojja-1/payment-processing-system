package com.portfolio.paymentsystem.reconciliation;

/** What PostgreSQL reports for the transaction the current connection is in. */
public record TransactionCharacteristics(String isolation, boolean readOnly) {

    /** PostgreSQL's spelling of {@code REPEATABLE READ} in {@code transaction_isolation}. */
    static final String REPEATABLE_READ = "repeatable read";

    public boolean isReadOnlyRepeatableRead() {
        return readOnly && REPEATABLE_READ.equalsIgnoreCase(isolation);
    }
}
