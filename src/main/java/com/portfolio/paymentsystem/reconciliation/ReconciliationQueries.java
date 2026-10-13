package com.portfolio.paymentsystem.reconciliation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The SQL behind reconciliation. <b>Every statement here is a {@code SELECT}</b>
 * (enforced by {@code ReconciliationQueriesTest}); nothing in this class can
 * change a balance or any other financial record.
 * <p>
 * Each check method returns at most {@code fetchLimit} findings. Callers pass
 * {@code cap + 1} so they can tell "exactly cap" from "more than cap" without
 * a separate COUNT query.
 * <p>
 * This class deliberately has no {@code @Transactional}: the transaction
 * attributes (read-only, REPEATABLE READ) are owned by
 * {@link ReconciliationService}, so that all checks share one snapshot.
 * Entry types and statuses are the same literals the schema uses:
 * {@code CREDIT} raises an account's balance and {@code DEBIT} lowers it.
 */
@Component
public class ReconciliationQueries {

    /** Signed contribution of one ledger line to its account's balance. */
    private static final String SIGNED_AMOUNT =
            "CASE e.entry_type WHEN 'CREDIT' THEN e.amount_paise ELSE -e.amount_paise END";

    static final String TRANSACTION_CHARACTERISTICS_SQL = """
            SELECT current_setting('transaction_isolation') AS isolation,
                   current_setting('transaction_read_only') AS read_only
            """;

    static final String R1_ACCOUNT_BALANCE_SQL = """
            SELECT a.id AS account_id, a.account_type, a.balance_paise,
                   COALESCE(SUM(%s), 0) AS ledger_balance_paise
            FROM accounts a
            LEFT JOIN ledger_entries e ON e.account_id = a.id
            GROUP BY a.id, a.account_type, a.balance_paise
            HAVING a.balance_paise <> COALESCE(SUM(%s), 0)
            ORDER BY a.id
            LIMIT :limit
            """.formatted(SIGNED_AMOUNT, SIGNED_AMOUNT);

    static final String R2_UNBALANCED_LEDGER_TRANSACTION_SQL = """
            SELECT t.id AS ledger_transaction_id, t.reference_type, t.reference_id,
                   COUNT(e.id) AS entry_count,
                   COALESCE(SUM(CASE WHEN e.entry_type = 'DEBIT' THEN e.amount_paise END), 0) AS debit_paise,
                   COALESCE(SUM(CASE WHEN e.entry_type = 'CREDIT' THEN e.amount_paise END), 0) AS credit_paise
            FROM ledger_transactions t
            LEFT JOIN ledger_entries e ON e.ledger_transaction_id = t.id
            GROUP BY t.id, t.reference_type, t.reference_id
            HAVING COUNT(e.id) < 2
                OR COALESCE(SUM(CASE WHEN e.entry_type = 'DEBIT' THEN e.amount_paise END), 0)
                   <> COALESCE(SUM(CASE WHEN e.entry_type = 'CREDIT' THEN e.amount_paise END), 0)
            ORDER BY t.id
            LIMIT :limit
            """;

    static final String R3_SYSTEM_TOTALS_SQL = """
            SELECT (SELECT COALESCE(SUM(balance_paise), 0) FROM accounts) AS balance_sum_paise,
                   (SELECT COALESCE(SUM(%s), 0) FROM ledger_entries e) AS ledger_sum_paise
            """.formatted(SIGNED_AMOUNT);

    static final String R4_PAYMENT_LEDGER_MISMATCH_SQL = """
            SELECT p.id AS payment_id, p.status, p.type, p.amount_paise, p.account_id,
                   lt.id AS ledger_transaction_id,
                   ue.entry_type AS account_entry_type,
                   ue.amount_paise AS account_entry_amount_paise
            FROM payment_transactions p
            LEFT JOIN ledger_transactions lt
                   ON lt.reference_type = 'PAYMENT_TRANSACTION' AND lt.reference_id = p.id
            LEFT JOIN ledger_entries ue
                   ON ue.ledger_transaction_id = lt.id AND ue.account_id = p.account_id
            WHERE (p.status = 'SUCCEEDED' AND (lt.id IS NULL
                       OR ue.id IS NULL
                       OR ue.amount_paise <> p.amount_paise
                       OR ue.entry_type <> CASE p.type WHEN 'DEPOSIT' THEN 'CREDIT' ELSE 'DEBIT' END))
               OR (p.status <> 'SUCCEEDED' AND lt.id IS NOT NULL)
            ORDER BY p.id
            LIMIT :limit
            """;

    static final String R4_ORPHAN_PAYMENT_LEDGER_SQL = """
            SELECT lt.id AS ledger_transaction_id, lt.reference_id
            FROM ledger_transactions lt
            WHERE lt.reference_type = 'PAYMENT_TRANSACTION'
              AND NOT EXISTS (SELECT 1 FROM payment_transactions p WHERE p.id = lt.reference_id)
            ORDER BY lt.id
            LIMIT :limit
            """;

    static final String R5_TRANSFER_LEDGER_MISMATCH_SQL = """
            SELECT t.id AS transfer_id, t.status, t.amount_paise,
                   t.source_account_id, t.destination_account_id,
                   lt.id AS ledger_transaction_id,
                   de.amount_paise AS debit_amount_paise,
                   ce.amount_paise AS credit_amount_paise
            FROM transfers t
            LEFT JOIN ledger_transactions lt
                   ON lt.reference_type = 'TRANSFER' AND lt.reference_id = t.id
            LEFT JOIN ledger_entries de
                   ON de.ledger_transaction_id = lt.id
                  AND de.account_id = t.source_account_id AND de.entry_type = 'DEBIT'
            LEFT JOIN ledger_entries ce
                   ON ce.ledger_transaction_id = lt.id
                  AND ce.account_id = t.destination_account_id AND ce.entry_type = 'CREDIT'
            WHERE (t.status = 'COMPLETED' AND (lt.id IS NULL
                       OR de.id IS NULL
                       OR ce.id IS NULL
                       OR de.amount_paise <> t.amount_paise
                       OR ce.amount_paise <> t.amount_paise))
               OR (t.status <> 'COMPLETED' AND lt.id IS NOT NULL)
            ORDER BY t.id
            LIMIT :limit
            """;

    static final String R5_ORPHAN_TRANSFER_LEDGER_SQL = """
            SELECT lt.id AS ledger_transaction_id, lt.reference_id
            FROM ledger_transactions lt
            WHERE lt.reference_type = 'TRANSFER'
              AND NOT EXISTS (SELECT 1 FROM transfers t WHERE t.id = lt.reference_id)
            ORDER BY lt.id
            LIMIT :limit
            """;

    static final String R6_FAILED_OUTBOX_SQL = """
            SELECT id, aggregate_type, aggregate_id, event_type, status, attempt_count, created_at
            FROM outbox_events
            WHERE status = 'FAILED'
            ORDER BY created_at, id
            LIMIT :limit
            """;

    static final String R6_STALE_OUTBOX_SQL = """
            SELECT id, aggregate_type, aggregate_id, event_type, status, attempt_count, next_attempt_at
            FROM outbox_events
            WHERE status IN ('PENDING', 'PUBLISHING')
              AND next_attempt_at < now() - (:staleSeconds * INTERVAL '1 second')
            ORDER BY next_attempt_at, id
            LIMIT :limit
            """;

    static final String R6_STALE_PAYMENT_SQL = """
            SELECT id, type, status, attempt_count, updated_at
            FROM payment_transactions
            WHERE status IN ('PENDING', 'PROCESSING', 'RETRYING')
              AND updated_at < now() - (:staleSeconds * INTERVAL '1 second')
            ORDER BY updated_at, id
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ReconciliationQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All SQL this component can run; used by tests to assert it is read-only. */
    static List<String> allSql() {
        return List.of(
                TRANSACTION_CHARACTERISTICS_SQL,
                R1_ACCOUNT_BALANCE_SQL,
                R2_UNBALANCED_LEDGER_TRANSACTION_SQL,
                R3_SYSTEM_TOTALS_SQL,
                R4_PAYMENT_LEDGER_MISMATCH_SQL,
                R4_ORPHAN_PAYMENT_LEDGER_SQL,
                R5_TRANSFER_LEDGER_MISMATCH_SQL,
                R5_ORPHAN_TRANSFER_LEDGER_SQL,
                R6_FAILED_OUTBOX_SQL,
                R6_STALE_OUTBOX_SQL,
                R6_STALE_PAYMENT_SQL);
    }

    /** Reports the isolation level and read-only flag of the transaction this call runs in. */
    public TransactionCharacteristics transactionCharacteristics() {
        return jdbc.getJdbcTemplate().queryForObject(
                TRANSACTION_CHARACTERISTICS_SQL,
                (rs, row) -> new TransactionCharacteristics(
                        rs.getString("isolation"), "on".equalsIgnoreCase(rs.getString("read_only"))));
    }

    /** R1: materialized account balance must equal the signed sum of its ledger entries. */
    public List<Finding> accountBalanceMismatches(int fetchLimit) {
        return jdbc.query(R1_ACCOUNT_BALANCE_SQL, limit(fetchLimit), (rs, row) -> {
            long balance = rs.getLong("balance_paise");
            long ledger = rs.getLong("ledger_balance_paise");
            return new Finding("ACCOUNT", rs.getObject("account_id", UUID.class),
                    "Account balance does not equal the signed sum of its ledger entries",
                    details("accountType", rs.getString("account_type"),
                            "balancePaise", balance,
                            "ledgerBalancePaise", ledger,
                            "differencePaise", balance - ledger));
        });
    }

    /** R2: every ledger transaction must balance (debits = credits) and have at least two entries. */
    public List<Finding> unbalancedLedgerTransactions(int fetchLimit) {
        return jdbc.query(R2_UNBALANCED_LEDGER_TRANSACTION_SQL, limit(fetchLimit), (rs, row) -> {
            long debit = rs.getLong("debit_paise");
            long credit = rs.getLong("credit_paise");
            long entries = rs.getLong("entry_count");
            return new Finding("LEDGER_TRANSACTION", rs.getObject("ledger_transaction_id", UUID.class),
                    entries < 2
                            ? "Ledger transaction has fewer than two entries"
                            : "Ledger transaction debits do not equal credits",
                    details("entryCount", entries,
                            "debitPaise", debit,
                            "creditPaise", credit,
                            "referenceType", rs.getString("reference_type"),
                            "referenceId", rs.getObject("reference_id", UUID.class)));
        });
    }

    /** R3: account balances and signed ledger entries must each sum to zero across the whole system. */
    public List<Finding> systemTotalViolations() {
        return jdbc.getJdbcTemplate().query(R3_SYSTEM_TOTALS_SQL, rs -> {
            List<Finding> findings = new ArrayList<>();
            if (rs.next()) {
                long balanceSum = rs.getLong("balance_sum_paise");
                long ledgerSum = rs.getLong("ledger_sum_paise");
                if (balanceSum != 0) {
                    findings.add(new Finding("SYSTEM", null,
                            "Sum of all account balances (including the clearing account) is not zero",
                            details("balanceSumPaise", balanceSum)));
                }
                if (ledgerSum != 0) {
                    findings.add(new Finding("SYSTEM", null,
                            "Signed sum of all ledger entries is not zero",
                            details("ledgerSumPaise", ledgerSum)));
                }
            }
            return findings;
        });
    }

    /** R4: payment status vs. its settlement ledger transaction, plus orphaned payment ledger references. */
    public List<Finding> paymentLedgerMismatches(int fetchLimit) {
        List<Finding> findings = new ArrayList<>(jdbc.query(R4_PAYMENT_LEDGER_MISMATCH_SQL, limit(fetchLimit), (rs, row) -> {
            boolean succeeded = "SUCCEEDED".equals(rs.getString("status"));
            return new Finding("PAYMENT_TRANSACTION", rs.getObject("payment_id", UUID.class),
                    succeeded
                            ? "SUCCEEDED payment has no matching settlement ledger entry"
                            : "Payment that is not SUCCEEDED has a settlement ledger transaction",
                    details("status", rs.getString("status"),
                            "type", rs.getString("type"),
                            "amountPaise", rs.getLong("amount_paise"),
                            "accountId", rs.getObject("account_id", UUID.class),
                            "ledgerTransactionId", rs.getObject("ledger_transaction_id", UUID.class),
                            "accountEntryType", rs.getString("account_entry_type"),
                            "accountEntryAmountPaise", rs.getObject("account_entry_amount_paise", Long.class)));
        }));
        findings.addAll(jdbc.query(R4_ORPHAN_PAYMENT_LEDGER_SQL, limit(fetchLimit), orphanMapper(
                "Ledger transaction references a payment that does not exist")));
        return findings;
    }

    /** R5: completed transfers need the right debit/credit; non-completed transfers must have no ledger transaction. */
    public List<Finding> transferLedgerMismatches(int fetchLimit) {
        List<Finding> findings = new ArrayList<>(jdbc.query(R5_TRANSFER_LEDGER_MISMATCH_SQL, limit(fetchLimit), (rs, row) -> {
            boolean completed = "COMPLETED".equals(rs.getString("status"));
            return new Finding("TRANSFER", rs.getObject("transfer_id", UUID.class),
                    completed
                            ? "COMPLETED transfer is missing or has incorrect debit/credit ledger entries"
                            : "Transfer that is not COMPLETED has a ledger transaction",
                    details("status", rs.getString("status"),
                            "amountPaise", rs.getLong("amount_paise"),
                            "sourceAccountId", rs.getObject("source_account_id", UUID.class),
                            "destinationAccountId", rs.getObject("destination_account_id", UUID.class),
                            "ledgerTransactionId", rs.getObject("ledger_transaction_id", UUID.class),
                            "debitAmountPaise", rs.getObject("debit_amount_paise", Long.class),
                            "creditAmountPaise", rs.getObject("credit_amount_paise", Long.class)));
        }));
        findings.addAll(jdbc.query(R5_ORPHAN_TRANSFER_LEDGER_SQL, limit(fetchLimit), orphanMapper(
                "Ledger transaction references a transfer that does not exist")));
        return findings;
    }

    /** R6: failed outbox events, plus stale pending/publishing outbox rows and stale non-terminal payments. */
    public List<Finding> failedOrStaleWork(int fetchLimit, long staleSeconds) {
        MapSqlParameterSource params = limit(fetchLimit).addValue("staleSeconds", staleSeconds);
        List<Finding> findings = new ArrayList<>();

        findings.addAll(jdbc.query(R6_FAILED_OUTBOX_SQL, params, (rs, row) -> outboxFinding(
                rs.getObject("id", UUID.class), "Outbox event has FAILED (publishing attempts exhausted)",
                "createdAt", rs.getTimestamp("created_at").toInstant(), rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"),
                rs.getString("status"), rs.getInt("attempt_count"))));

        findings.addAll(jdbc.query(R6_STALE_OUTBOX_SQL, params, (rs, row) -> outboxFinding(
                rs.getObject("id", UUID.class), "Outbox event is overdue and has not been published",
                "nextAttemptAt", rs.getTimestamp("next_attempt_at").toInstant(), rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"),
                rs.getString("status"), rs.getInt("attempt_count"))));

        findings.addAll(jdbc.query(R6_STALE_PAYMENT_SQL, params, (rs, row) ->
                new Finding("PAYMENT_TRANSACTION", rs.getObject("id", UUID.class),
                        "Payment has been in a non-terminal status for longer than the stale threshold",
                        details("type", rs.getString("type"),
                                "status", rs.getString("status"),
                                "attemptCount", rs.getInt("attempt_count"),
                                "lastUpdatedAt", rs.getTimestamp("updated_at").toInstant()))));
        return findings;
    }

    private static Finding outboxFinding(UUID id, String message, String timeKey, Object time, String aggregateType,
            UUID aggregateId, String eventType, String status, int attemptCount) {
        return new Finding("OUTBOX_EVENT", id, message,
                details("aggregateType", aggregateType,
                        "aggregateId", aggregateId,
                        "eventType", eventType,
                        "status", status,
                        "attemptCount", attemptCount,
                        timeKey, time));
    }

    private static RowMapper<Finding> orphanMapper(String message) {
        return (rs, row) -> new Finding("LEDGER_TRANSACTION", rs.getObject("ledger_transaction_id", UUID.class),
                message, details("referenceId", rs.getObject("reference_id", UUID.class)));
    }

    private static MapSqlParameterSource limit(int fetchLimit) {
        return new MapSqlParameterSource("limit", fetchLimit);
    }

    /** Insertion-ordered map that, unlike {@code Map.of}, tolerates null values. */
    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
