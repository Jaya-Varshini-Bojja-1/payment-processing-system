package com.portfolio.paymentsystem.identity.exception;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * Thrown for any login failure (unknown email, wrong password, inactive
 * account). The message is intentionally generic so responses do not reveal
 * whether an email is registered (avoids account enumeration).
 */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }
}
