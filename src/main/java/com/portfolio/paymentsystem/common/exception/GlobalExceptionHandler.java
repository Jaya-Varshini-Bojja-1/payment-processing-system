package com.portfolio.paymentsystem.common.exception;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    private static final URI PROBLEM_TYPE_BASE = URI.create("https://payment-processing-system.local/problems/");

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getStatus().getReasonPhrase());
        problem.setType(PROBLEM_TYPE_BASE.resolve(ex.getErrorCode()));
        problem.setProperty("code", ex.getErrorCode());
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
        problem.setType(PROBLEM_TYPE_BASE.resolve("optimistic-lock-conflict"));
        problem.setProperty("code", "OPTIMISTIC_LOCK_CONFLICT");
        return problem;
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
        problem.setType(PROBLEM_TYPE_BASE.resolve("validation-failed"));
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("errors", fieldErrors);
        return problem;
    }
}
