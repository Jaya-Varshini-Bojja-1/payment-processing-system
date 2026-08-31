package com.portfolio.paymentsystem.common.idempotency.exception;

/**
 * Not a "new" error — a signal to render a <b>previously computed</b> failure
 * response verbatim. Thrown when {@code IdempotencyService#claim} returns a
 * {@code FAILED} record: the original attempt already reached a definitive
 * business outcome (e.g. insufficient balance), which was cached specifically
 * so a retry never re-runs that logic and always sees the identical answer.
 * <p>
 * Deliberately does not extend {@link com.portfolio.paymentsystem.common.exception.ApiException}
 * — its status/body are arbitrary and already fully formed, unlike a fresh
 * {@code ApiException} whose {@link org.springframework.http.ProblemDetail}
 * still needs to be built. See the dedicated handler in
 * {@code GlobalExceptionHandler}.
 */
public class IdempotentReplayException extends RuntimeException {

    private final int httpStatus;
    private final transient Object body;

    public IdempotentReplayException(int httpStatus, Object body) {
        super("Replaying cached idempotent response with status " + httpStatus);
        this.httpStatus = httpStatus;
        this.body = body;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public Object getBody() {
        return body;
    }
}
