package com.portfolio.paymentsystem.ledger.exception;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * Thrown for structural problems with a proposed journal post other than
 * "unbalanced": fewer than two lines, a non-positive amount, or a
 * currency other than INR.
 */
public class InvalidLedgerPostingException extends ApiException {

    public InvalidLedgerPostingException(String reason) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_LEDGER_POSTING", reason);
    }
}
