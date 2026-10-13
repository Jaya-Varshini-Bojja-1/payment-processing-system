package com.portfolio.paymentsystem.reconciliation;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Outcome of one reconciliation check")
public record CheckResult(
        @Schema(example = "R1") String checkId,
        String name,
        Severity severity,
        @Schema(description = "Number of findings included in this result (bounded by the configured cap)")
        int violationsReturned,
        @Schema(description = "True when more violations exist than were returned")
        boolean truncated,
        List<Finding> findings
) {
}
