package com.portfolio.paymentsystem.identity.exception;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * Thrown when a refresh token is missing, malformed, expired, revoked, or
 * (in the reuse-detection case) already rotated.
 */
public class InvalidRefreshTokenException extends ApiException {

    public InvalidRefreshTokenException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid, expired, or revoked");
    }
}
