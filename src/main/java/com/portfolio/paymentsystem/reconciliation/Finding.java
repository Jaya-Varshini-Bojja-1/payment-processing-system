package com.portfolio.paymentsystem.reconciliation;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One concrete inconsistency. Identifiers and numbers only: never emails,
 * tokens or other personal data.
 */
@Schema(description = "A single reconciliation finding (identifiers and amounts only)")
public record Finding(
        @Schema(example = "ACCOUNT", description = "What kind of record the finding is about; SYSTEM for system-wide totals")
        String subjectType,
        @Schema(description = "Id of the offending record; null for system-wide findings")
        UUID subjectId,
        String message,
        @Schema(description = "Expected versus actual values and related ids")
        Map<String, Object> details
) {
}
