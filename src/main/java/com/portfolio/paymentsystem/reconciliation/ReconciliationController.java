package com.portfolio.paymentsystem.reconciliation;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Admin-only, read-only reconciliation report. There is intentionally no
 * admin registration or login flow: a user can only reach this endpoint after
 * a deliberate, manual, development-only promotion to {@code ADMIN} in the
 * database (see README).
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@Tag(name = "Admin", description = "Operational, read-only tooling (ADMIN role only)")
@SecurityRequirement(name = "bearerAuth")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Run a read-only reconciliation",
            description = "Checks account balances against the ledger, ledger balance, system-wide totals, payment and "
                    + "transfer settlement, and reports failed or stale outbox/payment work. Runs in a read-only "
                    + "REPEATABLE READ transaction; it never modifies balances or financial records.")
    @ApiResponse(responseCode = "200", description = "Report generated (check the `clean` flag)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
    @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role")
    public ReconciliationReport reconcile() {
        return reconciliationService.reconcile();
    }
}
