package com.portfolio.paymentsystem.wallet.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

public class AccountNotFoundException extends ApiException {

    private AccountNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", message);
    }

    public static AccountNotFoundException byId(UUID accountId) {
        return new AccountNotFoundException("Account '" + accountId + "' does not exist");
    }

    public static AccountNotFoundException byUser(UUID userId) {
        return new AccountNotFoundException("User '" + userId + "' does not have an account yet");
    }
}
