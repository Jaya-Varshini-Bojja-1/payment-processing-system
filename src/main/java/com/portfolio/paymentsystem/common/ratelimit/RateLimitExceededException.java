package com.portfolio.paymentsystem.common.ratelimit;

/**
 * Thrown by {@link RateLimitInterceptor} when a user has exceeded their
 * request budget for a given {@link RateLimitScope}.
 * <p>
 * Deliberately does not extend {@code ApiException}: a {@code 429} also
 * needs a {@code Retry-After} response header, which {@code ApiException}'s
 * {@code ProblemDetail}-only contract has no place for. See
 * {@code GlobalExceptionHandler#handleRateLimitExceeded} for the dedicated
 * handler that builds the header.
 */
public class RateLimitExceededException extends RuntimeException {

    private final RateLimitScope scope;
    private final long retryAfterSeconds;

    public RateLimitExceededException(RateLimitScope scope, long retryAfterSeconds) {
        super("Rate limit exceeded for scope " + scope + "; retry after " + retryAfterSeconds + "s");
        this.scope = scope;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public RateLimitScope getScope() {
        return scope;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
