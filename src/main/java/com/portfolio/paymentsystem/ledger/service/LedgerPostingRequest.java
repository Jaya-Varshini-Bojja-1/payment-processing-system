package com.portfolio.paymentsystem.ledger.service;

import java.util.List;
import java.util.UUID;

/**
 * The full input to {@code LedgerService#postTransaction}: a set of
 * balanced lines plus an optional business reference used to guard against
 * double-posting the same event (see {@code LedgerTransaction}).
 * <p>
 * {@code referenceType}/{@code referenceId} are both null or both set —
 * partial references defeat the point of the uniqueness guard.
 */
public record LedgerPostingRequest(
        List<LedgerLineItem> lines,
        String referenceType,
        UUID referenceId,
        String description) {

    public LedgerPostingRequest {
        lines = List.copyOf(lines);
        if ((referenceType == null) != (referenceId == null)) {
            throw new IllegalArgumentException("referenceType and referenceId must both be null or both be set");
        }
    }

    public static LedgerPostingRequest of(List<LedgerLineItem> lines, String description) {
        return new LedgerPostingRequest(lines, null, null, description);
    }

    public static LedgerPostingRequest withReference(
            List<LedgerLineItem> lines, String referenceType, UUID referenceId, String description) {
        return new LedgerPostingRequest(lines, referenceType, referenceId, description);
    }
}
