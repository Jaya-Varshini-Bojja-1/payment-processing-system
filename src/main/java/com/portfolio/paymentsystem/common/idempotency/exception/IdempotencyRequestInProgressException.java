package com.portfolio.paymentsystem.common.idempotency.exception;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * A genuine concurrent duplicate: another request with the same
 * {@code Idempotency-Key} (and matching request body) is still being
 * processed right now. M3 deliberately does not block/poll waiting for the
 * in-flight request to finish (that would tie up a request thread for an
 * unbounded time and adds its own failure modes); the client is expected
 * to retry shortly with the same key, at which point it will either see
 * this again (still running) or the cached final outcome.
 */
public class IdempotencyRequestInProgressException extends ApiException {

    public IdempotencyRequestInProgressException(String idempotencyKey) {
        super(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                "A request with Idempotency-Key '" + idempotencyKey + "' is already being processed");
    }
}
