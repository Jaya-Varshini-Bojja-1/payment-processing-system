package com.portfolio.paymentsystem.identity.exception;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

public class EmailAlreadyExistsException extends ApiException {

    public EmailAlreadyExistsException(String email) {
        super(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "An account with email '" + email + "' already exists");
    }
}
