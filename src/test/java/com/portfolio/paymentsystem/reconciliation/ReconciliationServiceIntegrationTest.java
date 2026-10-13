package com.portfolio.paymentsystem.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises the service's transactional guarantees against the real
 * PostgreSQL. Deliberately <b>not</b> {@code @Transactional}: the service must
 * open its own read-only REPEATABLE READ transaction, and an outer test
 * transaction would silently join (and weaken) it. Nothing here seeds data,
 * so nothing needs cleaning up.
 * <p>
 * Findings detection is covered by {@code ReconciliationQueriesIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
class ReconciliationServiceIntegrationTest {

    @Autowired
    private ReconciliationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void runsInItsOwnReadOnlyRepeatableReadTransaction() {
        assertThat(TestTransaction.isActive()).as("no outer test transaction").isFalse();

        // The service refuses to run unless PostgreSQL reports a read-only
        // REPEATABLE READ transaction, so completing at all proves the
        // @Transactional attributes were applied to the real connection.
        ReconciliationReport report = service.reconcile();

        assertThat(report.checks()).extracting(CheckResult::checkId)
                .containsExactly("R1", "R2", "R3", "R4", "R5", "R6");
        assertThat(report.checks()).extracting(CheckResult::severity).containsExactly(
                Severity.ERROR, Severity.ERROR, Severity.ERROR, Severity.ERROR, Severity.ERROR, Severity.WARNING);
        assertThat(report.generatedAt()).isNotNull();
    }

    @Test
    void cleanFlagIsExactlyTheAbsenceOfErrorFindings() {
        // The shared database may or may not hold unrelated inconsistencies, so only the rule is asserted.
        ReconciliationReport report = service.reconcile();

        assertThat(report.clean()).isEqualTo(report.errorCount() == 0);
        assertThat(report.errorCount()).isEqualTo(report.checks().stream()
                .filter(c -> c.severity() == Severity.ERROR).mapToInt(CheckResult::violationsReturned).sum());
        assertThat(report.warningCount()).isEqualTo(report.checks().stream()
                .filter(c -> c.severity() == Severity.WARNING).mapToInt(CheckResult::violationsReturned).sum());
    }

    @Test
    void refusesToJoinAReadWriteReadCommittedCallerTransaction() {
        TransactionTemplate readWrite = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> readWrite.executeWithoutResult(status -> service.reconcile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only REPEATABLE READ");
    }

    @Test
    void runningItChangesNoFinancialRecords() {
        Map<String, Object> before = fingerprint();

        service.reconcile();

        assertThat(fingerprint()).isEqualTo(before);
    }

    /**
     * Row counts and balance/version aggregates of every financial table.
     * (Assumes nothing else is writing to the database while this test runs;
     * the test profile disables the background outbox publisher.)
     */
    private Map<String, Object> fingerprint() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT (SELECT COUNT(*) FROM accounts) AS accounts,
                       (SELECT COALESCE(SUM(balance_paise), 0) FROM accounts) AS balance_sum,
                       (SELECT COALESCE(SUM(version), 0) FROM accounts) AS version_sum,
                       (SELECT COUNT(*) FROM ledger_transactions) AS ledger_transactions,
                       (SELECT COUNT(*) FROM ledger_entries) AS ledger_entries,
                       (SELECT COUNT(*) FROM payment_transactions) AS payments,
                       (SELECT COUNT(*) FROM transfers) AS transfers,
                       (SELECT COUNT(*) FROM outbox_events) AS outbox_events,
                       (SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED') AS outbox_published,
                       (SELECT COUNT(*) FROM inbox_events) AS inbox_events
                """);
        return rows.get(0);
    }
}
