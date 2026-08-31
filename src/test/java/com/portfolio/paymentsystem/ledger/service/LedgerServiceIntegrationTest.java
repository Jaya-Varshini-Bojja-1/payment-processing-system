package com.portfolio.paymentsystem.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.portfolio.paymentsystem.common.domain.Money;
import com.portfolio.paymentsystem.identity.domain.User;
import com.portfolio.paymentsystem.identity.repository.UserRepository;
import com.portfolio.paymentsystem.ledger.domain.LedgerTransaction;
import com.portfolio.paymentsystem.ledger.exception.DuplicateLedgerPostingException;
import com.portfolio.paymentsystem.wallet.domain.Account;
import com.portfolio.paymentsystem.wallet.repository.AccountRepository;

/**
 * Exercises {@link LedgerService} against the real, host-local PostgreSQL
 * 18 instance the app uses (no Testcontainers, per the project plan) —
 * things a mocked unit test cannot prove: the ledger + balance actually
 * commit together, the database's own constraints/triggers hold, and real
 * concurrent transactions behave as the optimistic-locking design predicts.
 * <p>
 * Deliberately NOT class-annotated {@code @Transactional}: each call under
 * test must be a genuine, separately-committed database transaction (both
 * for "did this really persist" checks and for the concurrency test, where
 * multiple real transactions must run at once).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
class LedgerServiceIntegrationTest {

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Test fixture only: seeds a starting balance directly via SQL,
     * bypassing {@link LedgerService} entirely. Production code has no
     * equivalent path — every real balance change goes through the ledger.
     * This stands in for the "money enters the system" step that a real
     * system clearing account (M5, deposits) will own; M2 has no such
     * account yet, so tests fund accounts this way instead.
     */
    private Account createAccountFundedWith(long balancePaise) {
        User user = userRepository.save(User.newUser("ledger-it-" + UUID.randomUUID() + "@example.com", "unused-hash"));
        Account account = accountRepository.save(Account.openInr(user.getId()));
        jdbcTemplate.update("UPDATE accounts SET balance_paise = ? WHERE id = ?", balancePaise, account.getId());
        return accountRepository.findById(account.getId()).orElseThrow();
    }

    private Long countLedgerEntries(UUID ledgerTransactionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE ledger_transaction_id = ?", Long.class, ledgerTransactionId);
    }

    @Test
    void ledgerAndBalanceCommitTogetherInOneTransaction() {
        Account source = createAccountFundedWith(1_000_000);
        Account destination = createAccountFundedWith(0);

        LedgerTransaction posted = ledgerService.postTransaction(LedgerPostingRequest.of(List.of(
                LedgerLineItem.debit(source.getId(), Money.inr(250_000)),
                LedgerLineItem.credit(destination.getId(), Money.inr(250_000))
        ), "integration test movement"));

        Account reloadedSource = accountRepository.findById(source.getId()).orElseThrow();
        Account reloadedDestination = accountRepository.findById(destination.getId()).orElseThrow();

        assertThat(reloadedSource.getBalancePaise()).isEqualTo(750_000);
        assertThat(reloadedDestination.getBalancePaise()).isEqualTo(250_000);
        assertThat(countLedgerEntries(posted.getId())).isEqualTo(2);
    }

    @Test
    void duplicateReferenceIsRejectedByTheDatabaseUniqueIndex() {
        Account source = createAccountFundedWith(1_000_000);
        Account destination = createAccountFundedWith(0);
        UUID referenceId = UUID.randomUUID();

        ledgerService.postTransaction(LedgerPostingRequest.withReference(
                List.of(
                        LedgerLineItem.debit(source.getId(), Money.inr(100_000)),
                        LedgerLineItem.credit(destination.getId(), Money.inr(100_000))),
                "TEST_EVENT", referenceId, "first attempt"));

        // Second attempt with the SAME reference must be rejected before any
        // balance change is applied — the header insert fails first.
        assertThatThrownBy(() -> ledgerService.postTransaction(LedgerPostingRequest.withReference(
                List.of(
                        LedgerLineItem.debit(source.getId(), Money.inr(100_000)),
                        LedgerLineItem.credit(destination.getId(), Money.inr(100_000))),
                "TEST_EVENT", referenceId, "duplicate attempt")))
                .isInstanceOf(DuplicateLedgerPostingException.class);

        Account reloadedSource = accountRepository.findById(source.getId()).orElseThrow();
        assertThat(reloadedSource.getBalancePaise()).isEqualTo(900_000);
    }

    @Test
    void ledgerRowsRejectUpdateAndDeleteAtTheDatabaseLevel() {
        Account source = createAccountFundedWith(1_000_000);
        Account destination = createAccountFundedWith(0);

        LedgerTransaction posted = ledgerService.postTransaction(LedgerPostingRequest.of(List.of(
                LedgerLineItem.debit(source.getId(), Money.inr(50_000)),
                LedgerLineItem.credit(destination.getId(), Money.inr(50_000))
        ), "immutability test"));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_entries SET amount_paise = 1 WHERE ledger_transaction_id = ?", posted.getId()))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM ledger_entries WHERE ledger_transaction_id = ?", posted.getId()))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_transactions SET description = 'tampered' WHERE id = ?", posted.getId()))
                .isInstanceOf(DataAccessException.class);

        assertThat(countLedgerEntries(posted.getId())).isEqualTo(2);
    }

    @Test
    void concurrentPostingsToTheSameAccountNeverLoseAnUpdate() throws Exception {
        // "genesis" plays the part M5's system clearing account will play:
        // a large, pre-funded counterparty so every thread's debit/credit
        // pair is well-formed without needing per-thread seeding.
        Account genesis = createAccountFundedWith(100_000_000);
        Account hotTarget = createAccountFundedWith(0);

        int threadCount = 8;
        long perThreadAmountPaise = 1_000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    ledgerService.postTransaction(LedgerPostingRequest.of(List.of(
                            LedgerLineItem.debit(genesis.getId(), Money.inr(perThreadAmountPaise)),
                            LedgerLineItem.credit(hotTarget.getId(), Money.inr(perThreadAmountPaise))
                    ), "concurrency test"));
                    successCount.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    // Expected for the loser(s) of the version race — see
                    // LedgerService's class-level "concurrency contract"
                    // javadoc: this method does not retry, by design.
                    conflictCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        for (Future<?> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(successCount.get() + conflictCount.get()).isEqualTo(threadCount);
        // With 8 threads racing to update the SAME row starting from the
        // same version, at least one must lose — that is the entire point
        // of optimistic locking (no lost updates, ever).
        assertThat(conflictCount.get()).isGreaterThan(0);

        Account reloadedTarget = accountRepository.findById(hotTarget.getId()).orElseThrow();
        Account reloadedGenesis = accountRepository.findById(genesis.getId()).orElseThrow();
        long expectedMoved = successCount.get() * perThreadAmountPaise;
        assertThat(reloadedTarget.getBalancePaise()).isEqualTo(expectedMoved);
        assertThat(reloadedGenesis.getBalancePaise()).isEqualTo(100_000_000 - expectedMoved);
    }
}
