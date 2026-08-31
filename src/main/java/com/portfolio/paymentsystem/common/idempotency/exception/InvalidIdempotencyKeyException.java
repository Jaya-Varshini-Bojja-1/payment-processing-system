package com.portfolio.paymentsystem.common.idempotency.exception;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * The {@code Idempotency-Key} header was present but blank. A genuinely
 * missing header is instead caught by Spring's own
 * {@link org.springframework.web.bind.MissingRequestHeaderException}
 * (see {@code GlobalExceptionHandler}); this covers the "header sent but
 * empty" case that Spring does not treat as missing.
 */
public class InvalidIdempotencyKeyException extends ApiException {

    public InvalidIdempotencyKeyException() {
        super(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must not be blank");
    }
}
