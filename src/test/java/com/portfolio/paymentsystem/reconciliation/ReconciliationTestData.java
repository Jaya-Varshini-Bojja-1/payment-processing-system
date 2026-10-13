package com.portfolio.paymentsystem.reconciliation;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only raw SQL seeding for reconciliation tests.
 * <p>
 * Everything is plain {@code INSERT}. The ledger immutability triggers only
 * reject {@code UPDATE}/{@code DELETE} on {@code ledger_transactions} and
 * {@code ledger_entries}, so inserting deliberately inconsistent rows needs no
 * trigger tricks. Callers run inside a rolled-back test transaction, which is
 * what removes these rows afterwards (the triggers would otherwise make
 * cleaning up ledger rows impossible).
 */
final class ReconciliationTestData {

    private final JdbcTemplate jdbc;

    ReconciliationTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
                id, "recon-test-" + id + "@example.com", "!test-only!");
        return id;
    }

    UUID account(UUID userId, long balancePaise) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, user_id, currency, balance_paise, account_type) VALUES (?, ?, 'INR', ?, 'USER')",
                id, userId, balancePaise);
        return id;
    }

    /** A fresh user with a fresh account holding {@code balancePaise}. */
    UUID userAccount(long balancePaise) {
        return account(user(), balancePaise);
    }

    UUID ledgerTransaction(String referenceType, UUID referenceId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_transactions (id, reference_type, reference_id, description) VALUES (?, ?, ?, 'reconciliation test')",
                id, referenceType, referenceId);
        return id;
    }

    void entry(UUID ledgerTransactionId, UUID accountId, String entryType, long amountPaise) {
        jdbc.update("INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, entry_type, amount_paise) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), ledgerTransactionId, accountId, entryType, amountPaise);
    }

    /** A payment whose {@code updated_at} is {@code ageSeconds} in the past. */
    UUID payment(UUID userId, UUID accountId, String type, long amountPaise, String status, long ageSeconds) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_transactions (id, user_id, account_id, type, amount_paise, status, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now() - (? * INTERVAL '1 second'))
                """, id, userId, accountId, type, amountPaise, status, ageSeconds);
        return id;
    }

    UUID transfer(UUID initiatorUserId, UUID sourceAccountId, UUID destinationAccountId, UUID destinationUserId,
            long amountPaise, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transfers (id, initiator_user_id, source_account_id, destination_account_id,
                                       destination_user_id, amount_paise, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, initiatorUserId, sourceAccountId, destinationAccountId, destinationUserId, amountPaise, status);
        return id;
    }

    /** An outbox row whose {@code next_attempt_at} is {@code dueAgeSeconds} in the past. */
    UUID outbox(String status, long dueAgeSeconds) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, dlq_topic,
                                           payload_json, status, next_attempt_at)
                VALUES (?, 'ReconciliationTest', ?, 'ReconciliationTestEvent', 'reconciliation.test',
                        'reconciliation.test.dlq', '{}', ?, now() - (? * INTERVAL '1 second'))
                """, id, UUID.randomUUID(), status, dueAgeSeconds);
        return id;
    }
}
