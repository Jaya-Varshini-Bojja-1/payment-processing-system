package com.portfolio.paymentsystem.wallet.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * A user tried to read an account (or its ledger history) that is not
 * theirs. {@code 404} would leak less about existence, but {@code 403} is
 * clearer for a portfolio API and V1 has no admin bypass to hide behind
 * anyway.
 */
public class AccountAccessDeniedException extends ApiException {

    public AccountAccessDeniedException(UUID accountId) {
        super(HttpStatus.FORBIDDEN, "ACCOUNT_ACCESS_DENIED", "Account '" + accountId + "' does not belong to the caller");
    }
}
