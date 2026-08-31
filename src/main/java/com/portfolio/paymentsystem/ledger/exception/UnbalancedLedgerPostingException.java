package com.portfolio.paymentsystem.ledger.exception;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * Thrown when a proposed journal post's total credits do not equal its
 * total debits. This is the single most important invariant in the whole
 * system: if it were ever allowed through, money would be created or
 * destroyed. Checked in the service, before any row is written, so the
 * transaction never even attempts an insert.
 */
public class UnbalancedLedgerPostingException extends ApiException {

    public UnbalancedLedgerPostingException(long totalDebitPaise, long totalCreditPaise) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "UNBALANCED_LEDGER_POSTING",
                "Ledger posting is not balanced: total debit paise=" + totalDebitPaise
                        + " != total credit paise=" + totalCreditPaise);
    }
}
