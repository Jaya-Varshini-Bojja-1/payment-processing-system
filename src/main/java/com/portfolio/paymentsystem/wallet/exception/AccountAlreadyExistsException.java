package com.portfolio.paymentsystem.wallet.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * V1 allows exactly one account (wallet) per user. Thrown by
 * {@code AccountService#openAccount} when the caller already has one.
 */
public class AccountAlreadyExistsException extends ApiException {

    public AccountAlreadyExistsException(UUID userId) {
        super(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS", "User '" + userId + "' already has an account");
    }
}
