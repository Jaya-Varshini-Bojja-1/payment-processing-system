package com.portfolio.paymentsystem.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves each check detects a deliberately seeded inconsistency, against the
 * real local PostgreSQL.
 * <p>
 * <b>What this class does and does not cover.</b> The class is
 * {@code @Transactional}, so seeded rows (including ledger rows, which the
 * immutability triggers would otherwise make impossible to clean up) are
 * rolled back after each test and never pollute the shared database. Because
 * the outer test transaction is read-write READ COMMITTED, the queries here
 * run inside it. That is fine for proving the <i>SQL logic</i>, and it is why
 * these tests call {@link ReconciliationQueries} directly and NOT
 * {@link ReconciliationService}: the service's read-only REPEATABLE READ
 * guarantee is exercised separately, without any outer transaction, in
 * {@code ReconciliationServiceIntegrationTest}.
 * <p>
 * The database is shared with other tests and manual runs, so these tests
 * assert that the seeded ids are (or are not) among the findings; they never
 * assert that the whole database is otherwise clean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
@Transactional
class ReconciliationQueriesIntegrationTest {

    /** Large enough that rows already in the shared database cannot push seeded rows past the limit. */
    private static final int LIMIT = 100_000;
    private static final long STALE_SECONDS = 900;
    private static final long TWO_HOURS = 7_200;

    @Autowired
    private ReconciliationQueries queries;

    @Autowired
    private JdbcTemplate jdbc;

    private ReconciliationTestData data;

    @BeforeEach
    void setUp() {
        data = new ReconciliationTestData(jdbc);
    }

    private static Set<UUID> ids(List<Finding> findings) {
        return findings.stream().map(Finding::subjectId).collect(Collectors.toSet());
    }

    private static Finding byId(List<Finding> findings, UUID id) {
        return findings.stream().filter(f -> id.equals(f.subjectId())).findFirst()
                .orElseThrow(() -> new AssertionError("No finding for " + id));
    }

    // ---- R1 ---------------------------------------------------------------

    @Test
    void r1_flagsAccountWhoseBalanceDiffersFromItsLedger() {
        UUID mismatched = data.userAccount(100);   // balance 100, no ledger entries
        UUID consistent = data.userAccount(0);     // balance 0, no ledger entries

        List<Finding> findings = queries.accountBalanceMismatches(LIMIT);

        assertThat(ids(findings)).contains(mismatched).doesNotContain(consistent);
        assertThat(byId(findings, mismatched).details())
                .containsEntry("balancePaise", 100L)
                .containsEntry("ledgerBalancePaise", 0L)
                .containsEntry("differencePaise", 100L);
    }

    @Test
    void r1_flagsAccountWhoseLedgerHasMoneyTheBalanceLacks() {
        UUID creditedButZero = data.userAccount(0);
        UUID counterparty = data.userAccount(0);
        UUID tx = data.ledgerTransaction(null, null);
        data.entry(tx, creditedButZero, "CREDIT", 40);
        data.entry(tx, counterparty, "DEBIT", 40);

        List<Finding> findings = queries.accountBalanceMismatches(LIMIT);

        assertThat(byId(findings, creditedButZero).details())
                .containsEntry("balancePaise", 0L)
                .containsEntry("ledgerBalancePaise", 40L)
                .containsEntry("differencePaise", -40L);
        assertThat(byId(findings, counterparty).details()).containsEntry("ledgerBalancePaise", -40L);
    }

    // ---- R2 ---------------------------------------------------------------

    @Test
    void r2_flagsSingleEntryUnbalancedAndAcceptsBalancedLedgerTransactions() {
        UUID a = data.userAccount(0);
        UUID b = data.userAccount(0);

        UUID singleEntry = data.ledgerTransaction(null, null);
        data.entry(singleEntry, a, "CREDIT", 50);

        UUID unbalanced = data.ledgerTransaction(null, null);
        data.entry(unbalanced, a, "DEBIT", 50);
        data.entry(unbalanced, b, "CREDIT", 70);

        UUID balanced = data.ledgerTransaction(null, null);
        data.entry(balanced, a, "CREDIT", 60);
        data.entry(balanced, b, "DEBIT", 60);

        List<Finding> findings = queries.unbalancedLedgerTransactions(LIMIT);

        assertThat(ids(findings)).contains(singleEntry, unbalanced).doesNotContain(balanced);
        assertThat(byId(findings, singleEntry).message()).contains("fewer than two entries");
        assertThat(byId(findings, singleEntry).details()).containsEntry("entryCount", 1L);
        assertThat(byId(findings, unbalanced).details())
                .containsEntry("debitPaise", 50L)
                .containsEntry("creditPaise", 70L);
    }

    @Test
    void r2_flagsLedgerTransactionWithNoEntriesAtAll() {
        UUID empty = data.ledgerTransaction(null, null);

        assertThat(ids(queries.unbalancedLedgerTransactions(LIMIT))).contains(empty);
    }

    // ---- R3 ---------------------------------------------------------------

    @Test
    void r3_reportsNonZeroSystemTotals() {
        long balanceBefore = jdbc.queryForObject("SELECT COALESCE(SUM(balance_paise), 0) FROM accounts", Long.class);
        long ledgerBefore = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE entry_type WHEN 'CREDIT' THEN amount_paise ELSE -amount_paise END), 0)
                FROM ledger_entries
                """, Long.class);
        // Make the assertions below unambiguous even if the shared DB were already off by exactly these amounts.
        assumeTrue(balanceBefore + 100 != 0 && ledgerBefore + 70 != 0);

        UUID account = data.userAccount(100);          // balances now sum to balanceBefore + 100
        UUID tx = data.ledgerTransaction(null, null);
        data.entry(tx, account, "CREDIT", 70);         // ledger now sums to ledgerBefore + 70

        List<Finding> findings = queries.systemTotalViolations();

        assertThat(findings).anySatisfy(f -> assertThat(f.details()).containsEntry("balanceSumPaise", balanceBefore + 100));
        assertThat(findings).anySatisfy(f -> assertThat(f.details()).containsEntry("ledgerSumPaise", ledgerBefore + 70));
        assertThat(findings).allSatisfy(f -> {
            assertThat(f.subjectType()).isEqualTo("SYSTEM");
            assertThat(f.subjectId()).isNull();
        });
    }

    // ---- R4 ---------------------------------------------------------------

    @Test
    void r4_flagsSucceededPaymentWithoutLedgerAndNotSucceededPaymentWithLedger() {
        UUID user = data.user();
        UUID account = data.account(user, 0);
        UUID counterparty = data.userAccount(0);

        UUID succeededNoLedger = data.payment(user, account, "DEPOSIT", 5000, "SUCCEEDED", 0);

        UUID pendingButPosted = data.payment(user, account, "DEPOSIT", 5000, "PENDING", 0);
        UUID postedTx = data.ledgerTransaction("PAYMENT_TRANSACTION", pendingButPosted);
        data.entry(postedTx, account, "CREDIT", 5000);
        data.entry(postedTx, counterparty, "DEBIT", 5000);

        List<Finding> findings = queries.paymentLedgerMismatches(LIMIT);

        assertThat(ids(findings)).contains(succeededNoLedger, pendingButPosted);
        assertThat(byId(findings, succeededNoLedger).message()).contains("SUCCEEDED payment has no matching");
        assertThat(byId(findings, pendingButPosted).message()).contains("not SUCCEEDED");
    }

    @Test
    void r4_flagsSucceededPaymentWithWrongAmountOrWrongDirection() {
        UUID user = data.user();
        UUID account = data.account(user, 0);
        UUID counterparty = data.userAccount(0);

        UUID wrongAmount = data.payment(user, account, "DEPOSIT", 5000, "SUCCEEDED", 0);
        UUID wrongAmountTx = data.ledgerTransaction("PAYMENT_TRANSACTION", wrongAmount);
        data.entry(wrongAmountTx, account, "CREDIT", 4000);
        data.entry(wrongAmountTx, counterparty, "DEBIT", 4000);

        UUID wrongDirection = data.payment(user, account, "DEPOSIT", 5000, "SUCCEEDED", 0);
        UUID wrongDirectionTx = data.ledgerTransaction("PAYMENT_TRANSACTION", wrongDirection);
        data.entry(wrongDirectionTx, account, "DEBIT", 5000);   // a deposit must CREDIT the user
        data.entry(wrongDirectionTx, counterparty, "CREDIT", 5000);

        List<Finding> findings = queries.paymentLedgerMismatches(LIMIT);

        assertThat(ids(findings)).contains(wrongAmount, wrongDirection);
    }

    @Test
    void r4_acceptsCorrectlySettledDepositAndWithdrawal() {
        UUID user = data.user();
        UUID account = data.account(user, 0);
        UUID counterparty = data.userAccount(0);

        UUID deposit = data.payment(user, account, "DEPOSIT", 5000, "SUCCEEDED", 0);
        UUID depositTx = data.ledgerTransaction("PAYMENT_TRANSACTION", deposit);
        data.entry(depositTx, account, "CREDIT", 5000);
        data.entry(depositTx, counterparty, "DEBIT", 5000);

        UUID withdrawal = data.payment(user, account, "WITHDRAWAL", 3000, "SUCCEEDED", 0);
        UUID withdrawalTx = data.ledgerTransaction("PAYMENT_TRANSACTION", withdrawal);
        data.entry(withdrawalTx, account, "DEBIT", 3000);
        data.entry(withdrawalTx, counterparty, "CREDIT", 3000);

        UUID failedNoLedger = data.payment(user, account, "DEPOSIT", 5095, "FAILED", 0);

        assertThat(ids(queries.paymentLedgerMismatches(LIMIT))).doesNotContain(deposit, withdrawal, failedNoLedger);
    }

    @Test
    void r4_flagsLedgerTransactionReferencingMissingPayment() {
        UUID orphan = data.ledgerTransaction("PAYMENT_TRANSACTION", UUID.randomUUID());

        List<Finding> findings = queries.paymentLedgerMismatches(LIMIT);

        assertThat(ids(findings)).contains(orphan);
        assertThat(byId(findings, orphan).subjectType()).isEqualTo("LEDGER_TRANSACTION");
    }

    // ---- R5 ---------------------------------------------------------------

    @Test
    void r5_flagsCompletedTransferWithNoLedgerTransaction() {
        UUID fromUser = data.user();
        UUID toUser = data.user();
        UUID from = data.account(fromUser, 0);
        UUID to = data.account(toUser, 0);
        UUID transfer = data.transfer(fromUser, from, to, toUser, 2500, "COMPLETED");

        List<Finding> findings = queries.transferLedgerMismatches(LIMIT);

        assertThat(ids(findings)).contains(transfer);
        assertThat(byId(findings, transfer).message()).contains("COMPLETED transfer");
    }

    @Test
    void r5_flagsCompletedTransferWithWrongAmountOrWrongAccounts() {
        UUID fromUser = data.user();
        UUID toUser = data.user();
        UUID from = data.account(fromUser, 0);
        UUID to = data.account(toUser, 0);
        UUID stranger = data.userAccount(0);

        UUID wrongCredit = data.transfer(fromUser, from, to, toUser, 2500, "COMPLETED");
        UUID wrongCreditTx = data.ledgerTransaction("TRANSFER", wrongCredit);
        data.entry(wrongCreditTx, from, "DEBIT", 2500);
        data.entry(wrongCreditTx, to, "CREDIT", 2400);

        UUID wrongAccount = data.transfer(fromUser, from, to, toUser, 1000, "COMPLETED");
        UUID wrongAccountTx = data.ledgerTransaction("TRANSFER", wrongAccount);
        data.entry(wrongAccountTx, from, "DEBIT", 1000);
        data.entry(wrongAccountTx, stranger, "CREDIT", 1000);   // credited somebody other than the recipient

        assertThat(ids(queries.transferLedgerMismatches(LIMIT))).contains(wrongCredit, wrongAccount);
    }

    @Test
    void r5_acceptsCorrectCompletedTransfer() {
        UUID fromUser = data.user();
        UUID toUser = data.user();
        UUID from = data.account(fromUser, 0);
        UUID to = data.account(toUser, 0);
        UUID transfer = data.transfer(fromUser, from, to, toUser, 2500, "COMPLETED");
        UUID tx = data.ledgerTransaction("TRANSFER", transfer);
        data.entry(tx, from, "DEBIT", 2500);
        data.entry(tx, to, "CREDIT", 2500);

        assertThat(ids(queries.transferLedgerMismatches(LIMIT))).doesNotContain(transfer);
    }

    @Test
    void r5_flagsNonCompletedTransferThatHasALedgerTransaction() {
        UUID fromUser = data.user();
        UUID toUser = data.user();
        UUID from = data.account(fromUser, 0);
        UUID to = data.account(toUser, 0);
        UUID failed = data.transfer(fromUser, from, to, toUser, 2500, "FAILED");
        UUID tx = data.ledgerTransaction("TRANSFER", failed);
        data.entry(tx, from, "DEBIT", 2500);
        data.entry(tx, to, "CREDIT", 2500);

        List<Finding> findings = queries.transferLedgerMismatches(LIMIT);

        assertThat(ids(findings)).contains(failed);
        assertThat(byId(findings, failed).message()).contains("not COMPLETED");
    }

    @Test
    void r5_flagsLedgerTransactionReferencingMissingTransfer() {
        UUID orphan = data.ledgerTransaction("TRANSFER", UUID.randomUUID());

        assertThat(ids(queries.transferLedgerMismatches(LIMIT))).contains(orphan);
    }

    // ---- R6 ---------------------------------------------------------------

    @Test
    void r6_flagsFailedAndStaleOutboxEventsButNotFreshOnes() {
        UUID failed = data.outbox("FAILED", 0);
        UUID stalePending = data.outbox("PENDING", TWO_HOURS);
        UUID stalePublishing = data.outbox("PUBLISHING", TWO_HOURS);
        UUID freshPending = data.outbox("PENDING", 0);
        UUID publishedLongAgo = data.outbox("PUBLISHED", TWO_HOURS);

        List<Finding> findings = queries.failedOrStaleWork(LIMIT, STALE_SECONDS);

        assertThat(ids(findings)).contains(failed, stalePending, stalePublishing)
                .doesNotContain(freshPending, publishedLongAgo);
        assertThat(byId(findings, failed).subjectType()).isEqualTo("OUTBOX_EVENT");
    }

    @Test
    void r6_flagsStaleNonTerminalPaymentsButNotFreshOrTerminalOnes() {
        UUID user = data.user();
        UUID account = data.account(user, 0);
        UUID staleProcessing = data.payment(user, account, "DEPOSIT", 1000, "PROCESSING", TWO_HOURS);
        UUID staleRetrying = data.payment(user, account, "DEPOSIT", 1000, "RETRYING", TWO_HOURS);
        UUID freshPending = data.payment(user, account, "DEPOSIT", 1000, "PENDING", 0);
        UUID oldFailed = data.payment(user, account, "DEPOSIT", 5095, "FAILED", TWO_HOURS);
        UUID oldManualReview = data.payment(user, account, "DEPOSIT", 5091, "REQUIRES_MANUAL_REVIEW", TWO_HOURS);

        List<Finding> findings = queries.failedOrStaleWork(LIMIT, STALE_SECONDS);

        assertThat(ids(findings)).contains(staleProcessing, staleRetrying)
                .doesNotContain(freshPending, oldFailed, oldManualReview);
    }
}
