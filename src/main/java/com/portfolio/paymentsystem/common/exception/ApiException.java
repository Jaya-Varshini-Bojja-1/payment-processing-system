package com.portfolio.paymentsystem.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base type for domain/application errors that should be surfaced to API
 * clients as RFC 7807 Problem Details rather than a generic 500.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    protected ApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
