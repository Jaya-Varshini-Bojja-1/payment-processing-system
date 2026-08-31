package com.portfolio.paymentsystem.common.idempotency.exception;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * The same {@code Idempotency-Key} was sent twice by the same user with a
 * different request body (different {@code requestHash}). {@code 422}
 * (rather than {@code 409}) signals "the request itself is invalid given
 * prior context," distinct from a transient conflict a client should just
 * retry — per {@code PROJECT_PLAN.md} section 10.
 */
public class IdempotencyKeyReusedException extends ApiException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key '" + idempotencyKey + "' was already used with a different request");
    }
}
