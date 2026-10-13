package com.portfolio.paymentsystem.reconciliation;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The result of one read-only reconciliation run.
 * <p>
 * {@code clean} is true when no {@link Severity#ERROR} finding was reported.
 * {@link Severity#WARNING} findings (stuck or failed async work) are counted
 * separately and deliberately do not make the report dirty: they can be
 * transient and say nothing about whether the money is right.
 */
@Schema(description = "Read-only consistency report across ledger, balances, payments, transfers and outbox")
public record ReconciliationReport(
        Instant generatedAt,
        @Schema(description = "True when there are no ERROR findings; warnings do not affect this")
        boolean clean,
        @Schema(description = "ERROR findings reported (bounded per check by the cap)")
        int errorCount,
        @Schema(description = "WARNING findings reported (bounded per check by the cap)")
        int warningCount,
        List<CheckResult> checks
) {

    public static ReconciliationReport of(Instant generatedAt, List<CheckResult> checks) {
        int errors = countFor(checks, Severity.ERROR);
        int warnings = countFor(checks, Severity.WARNING);
        return new ReconciliationReport(generatedAt, errors == 0, errors, warnings, List.copyOf(checks));
    }

    private static int countFor(List<CheckResult> checks, Severity severity) {
        return checks.stream()
                .filter(check -> check.severity() == severity)
                .mapToInt(CheckResult::violationsReturned)
                .sum();
    }
}
