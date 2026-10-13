package com.portfolio.paymentsystem.common.exception;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.portfolio.paymentsystem.common.idempotency.exception.IdempotentReplayException;
import com.portfolio.paymentsystem.common.ratelimit.RateLimitExceededException;

/**
 * Central RFC 7807 (Problem Details) error mapping.
 * <p>
 * This is the M1 foundation: it maps auth-related {@link ApiException}s and
 * Bean Validation failures. Later milestones (idempotency conflicts,
 * insufficient funds, optimistic lock conflicts, etc.) extend this same
 * handler rather than introducing a parallel error-mapping mechanism.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        return ProblemDetailFactory.from(ex);
    }

    /**
     * Not a fresh error — {@link IdempotentReplayException} carries a
     * status/body already computed and cached by a prior attempt (see
     * {@code IdempotencyService#fail}). Rendered as-is so a replayed
     * business failure is byte-for-byte identical to the original.
     */
    @ExceptionHandler(IdempotentReplayException.class)
    public ResponseEntity<Object> handleIdempotentReplay(IdempotentReplayException ex) {
        return ResponseEntity.status(ex.getHttpStatus()).body(ex.getBody());
    }

    /**
     * Thrown by Spring MVC itself when {@code @RequestHeader("Idempotency-Key")}
     * is absent from a money-moving POST. Handled explicitly (rather than
     * relying on Spring Boot's default error page) so it comes back in the
     * same {@link ProblemDetail} shape as every other client error here.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingRequestHeader(MissingRequestHeaderException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Required header '" + ex.getHeaderName() + "' is missing");
        problem.setTitle("Bad Request");
        problem.setType(ProblemDetailFactory.typeUri("missing-required-header"));
        problem.setProperty("code", "MISSING_REQUIRED_HEADER");
        return problem;
    }

    /**
     * Raised by JPA/Hibernate when an {@code @Version} check fails at flush
     * time — i.e. another transaction committed a change to the same row
     * between our read and our write (M2's optimistic-locking story on
     * {@code Account}). Per the M2 design decision, this handler does
     * <b>not</b> retry; it surfaces a 409 so the caller (or a higher-level
     * service that understands the operation's idempotency story) decides
     * whether to retry.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockConflict(ObjectOptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The record was modified concurrently; reload and retry the operation");
        problem.setTitle("Conflict");
        problem.setType(ProblemDetailFactory.typeUri("optimistic-lock-conflict"));
        problem.setProperty("code", "OPTIMISTIC_LOCK_CONFLICT");
        return problem;
    }

    /**
     * M6: thrown by {@code RateLimitInterceptor} when a user exceeds their
     * per-scope budget on a money-moving POST. Handled separately from the
     * generic {@link ApiException} path (rather than making
     * {@link RateLimitExceededException} extend it) because a {@code 429}
     * also needs a {@code Retry-After} header, which {@code ApiException}'s
     * {@link ProblemDetail}-only contract has no place for.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests; retry after " + ex.getRetryAfterSeconds() + " second(s)");
        problem.setTitle("Too Many Requests");
        problem.setType(ProblemDetailFactory.typeUri("rate-limit-exceeded"));
        problem.setProperty("code", "RATE_LIMIT_EXCEEDED");

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", fieldError.getDefaultMessage() == null ? "invalid" : fieldError.getDefaultMessage()))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Bad Request");
        problem.setType(ProblemDetailFactory.typeUri("validation-failed"));
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("errors", fieldErrors);
        return problem;
    }
}
