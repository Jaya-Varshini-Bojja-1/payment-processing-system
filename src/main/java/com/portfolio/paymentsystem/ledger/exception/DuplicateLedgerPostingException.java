package com.portfolio.paymentsystem.ledger.exception;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * Thrown when a journal post's {@code (referenceType, referenceId)} has
 * already been used — the database's unique index caught an attempt to post
 * the same business event's ledger entries twice. A defense-in-depth guard:
 * the primary client-facing idempotency mechanism is the (M3)
 * {@code idempotency_records} table, consulted before this is ever reached.
 */
public class DuplicateLedgerPostingException extends ApiException {

    public DuplicateLedgerPostingException(String referenceType, java.util.UUID referenceId) {
        super(HttpStatus.CONFLICT, "DUPLICATE_LEDGER_POSTING",
                "A ledger transaction already exists for reference " + referenceType + ":" + referenceId);
    }
}
