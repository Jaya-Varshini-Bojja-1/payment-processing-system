package com.portfolio.paymentsystem.common.exception;

import java.net.URI;

import org.springframework.http.ProblemDetail;

/**
 * Builds the RFC 7807 {@link ProblemDetail} for an {@link ApiException}.
 * <p>
 * Pulled out of {@code GlobalExceptionHandler} (M3) so idempotent failure
 * replay can cache the <b>exact same JSON shape</b> a live failure would
 * have produced: {@code IdempotencyService#fail} stores the result of this
 * factory, and a retry with the same key gets that stored body back
 * verbatim — one source of truth for "what does this error look like on
 * the wire," used whether the response is fresh or replayed.
 */
public final class ProblemDetailFactory {

    private static final URI PROBLEM_TYPE_BASE = URI.create("https://payment-processing-system.local/problems/");

    private ProblemDetailFactory() {
    }

    public static ProblemDetail from(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getStatus().getReasonPhrase());
        problem.setType(typeUri(ex.getErrorCode()));
        problem.setProperty("code", ex.getErrorCode());
        return problem;
    }

    /** For the handful of framework exceptions (not {@link ApiException}) that build their own {@link ProblemDetail}. */
    public static URI typeUri(String code) {
        return PROBLEM_TYPE_BASE.resolve(code);
    }
}
