package com.portfolio.paymentsystem.transfer.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * The sender's account balance is less than the requested transfer amount
 * at the moment it was (re-)checked. Thrown from inside
 * {@code TransferExecutionService}'s single attempt, using a freshly read
 * {@code Account} — see that class for why this check must be repeated on
 * every optimistic-lock retry rather than only once up front.
 */
public class InsufficientBalanceException extends ApiException {

    public InsufficientBalanceException(UUID accountId, long availablePaise, long requestedPaise) {
        super(HttpStatus.BAD_REQUEST, "INSUFFICIENT_BALANCE",
                "Account '" + accountId + "' has balance " + availablePaise
                        + " paise, which is less than the requested " + requestedPaise + " paise");
    }
}
