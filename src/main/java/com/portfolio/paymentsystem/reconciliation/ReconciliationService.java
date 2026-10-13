package com.portfolio.paymentsystem.reconciliation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only consistency report over ledger, balances, payments, transfers
 * and the outbox. It <b>detects and reports; it never repairs</b>: there is
 * no write path from this class (see {@link ReconciliationQueries}).
 *
 * <h2>Why REPEATABLE READ</h2>
 * Under PostgreSQL's default READ COMMITTED each statement sees whatever
 * committed since the previous one. A transfer committing between the R1 and
 * R5 queries could then look like an inconsistency that never existed.
 * REPEATABLE READ gives every check the same snapshot. A read-only
 * transaction also makes PostgreSQL itself reject any write on the connection.
 *
 * <h2>No silent weaker transaction</h2>
 * {@code @Transactional} attributes are ignored when a method joins an
 * already-open transaction. Rather than quietly run with weaker guarantees
 * (for example inside a read-write READ COMMITTED caller), {@link #reconcile()}
 * asks PostgreSQL what transaction it is really in and refuses to run unless
 * it is read-only and REPEATABLE READ.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationQueries queries;
    private final ReconciliationProperties properties;

    public ReconciliationService(ReconciliationQueries queries, ReconciliationProperties properties) {
        this.queries = queries;
        this.properties = properties;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport reconcile() {
        requireReadOnlySnapshotTransaction();

        int cap = Math.max(1, properties.getMaxFindings());
        int fetchLimit = cap + 1;
        long staleSeconds = Math.max(1, properties.getStaleAfter().toSeconds());

        List<CheckResult> checks = new ArrayList<>();
        checks.add(run("R1", "Account balance equals signed ledger sum", Severity.ERROR, cap,
                limit -> queries.accountBalanceMismatches(limit), fetchLimit));
        checks.add(run("R2", "Every ledger transaction balances and has at least two entries", Severity.ERROR, cap,
                limit -> queries.unbalancedLedgerTransactions(limit), fetchLimit));
        checks.add(run("R3", "System-wide balances and ledger entries each sum to zero", Severity.ERROR, cap,
                limit -> queries.systemTotalViolations(), fetchLimit));
        checks.add(run("R4", "Payment status agrees with its settlement ledger transaction", Severity.ERROR, cap,
                limit -> queries.paymentLedgerMismatches(limit), fetchLimit));
        checks.add(run("R5", "Transfer status agrees with its ledger transaction", Severity.ERROR, cap,
                limit -> queries.transferLedgerMismatches(limit), fetchLimit));
        checks.add(run("R6", "Failed or stale outbox events and non-terminal payments", Severity.WARNING, cap,
                limit -> queries.failedOrStaleWork(limit, staleSeconds), fetchLimit));

        ReconciliationReport report = ReconciliationReport.of(Instant.now(), checks);
        log.info("Reconciliation finished clean={} errors={} warnings={}",
                report.clean(), report.errorCount(), report.warningCount());
        return report;
    }

    private void requireReadOnlySnapshotTransaction() {
        TransactionCharacteristics actual = queries.transactionCharacteristics();
        if (!actual.isReadOnlyRepeatableRead()) {
            throw new IllegalStateException(
                    "Reconciliation must run in its own read-only REPEATABLE READ transaction, but the current "
                            + "transaction is isolation='" + actual.isolation() + "' readOnly=" + actual.readOnly()
                            + ". Do not call it from inside another transaction.");
        }
    }

    private static CheckResult run(String id, String name, Severity severity, int cap,
            IntFunction<List<Finding>> check, int fetchLimit) {
        List<Finding> found = check.apply(fetchLimit);
        boolean truncated = found.size() > cap;
        List<Finding> reported = truncated ? List.copyOf(found.subList(0, cap)) : List.copyOf(found);
        return new CheckResult(id, name, severity, reported.size(), truncated, reported);
    }
}
