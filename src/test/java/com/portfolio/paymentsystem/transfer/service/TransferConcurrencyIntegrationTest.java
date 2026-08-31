package com.portfolio.paymentsystem.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;

import com.portfolio.paymentsystem.common.idempotency.exception.IdempotencyRequestInProgressException;
import com.portfolio.paymentsystem.identity.domain.User;
import com.portfolio.paymentsystem.identity.repository.UserRepository;
import com.portfolio.paymentsystem.transfer.dto.CreateTransferRequest;
import com.portfolio.paymentsystem.transfer.exception.InsufficientBalanceException;
import com.portfolio.paymentsystem.transfer.exception.TransferConflictException;
import com.portfolio.paymentsystem.wallet.domain.Account;
import com.portfolio.paymentsystem.wallet.repository.AccountRepository;

/**
 * The proof of M3's two hardest correctness claims, against the real,
 * host-local PostgreSQL 18 + Redis (no mocks): concurrent transfers never
 * let a sender overspend (optimistic-lock retry + re-checked balance), and
 * concurrent duplicate requests sharing an {@code Idempotency-Key} move
 * money exactly once.
 * <p>
 * Calls {@link TransferService} directly rather than through HTTP/MockMvc —
 * same reasoning as {@code LedgerServiceIntegrationTest}: the behavior under
 * test lives entirely in the service layer, and driving it directly keeps
 * the test focused on that, not on the servlet stack.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
class TransferConcurrencyIntegrationTest {

    @Autowired
    private TransferService transferService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record UserAndAccount(UUID userId, UUID accountId) {
    }

    private UserAndAccount createUserAndAccount() {
        User user = userRepository.save(User.newUser("transfer-concurrency-it-" + UUID.randomUUID() + "@example.com", "unused-hash"));
        Account account = accountRepository.save(Account.openInr(user.getId()));
        return new UserAndAccount(user.getId(), account.getId());
    }

    /** Test fixture only, same pattern as {@code LedgerServiceIntegrationTest}: no deposit endpoint exists until M5. */
    private void fund(UUID accountId, long balancePaise) {
        jdbcTemplate.update("UPDATE accounts SET balance_paise = ? WHERE id = ?", balancePaise, accountId);
    }

    private long balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalancePaise();
    }

    /**
     * Sender can afford exactly 5 of 10 concurrently-fired transfers (each
     * a genuinely distinct operation — distinct {@code Idempotency-Key},
     * distinct recipient). Every attempt contends for the SAME sender
     * account row, so this exercises the full bounded optimistic-lock
     * retry loop under real contention, not just the balance check.
     * <p>
     * The only way this test could fail is a lost update (final balance
     * doesn't match successful transfers) or an overspend (final balance
     * goes negative, or more than 5 transfers succeed) — either would mean
     * the optimistic-lock retry design has a hole.
     */
    @Test
    void concurrentTransfersFromTheSameAccountNeverOverspendTheBalance() throws Exception {
        UserAndAccount sender = createUserAndAccount();
        fund(sender.accountId(), 100_000);
        long amountPerTransfer = 20_000;
        int attemptCount = 10;
        int affordableCount = 5;

        List<UserAndAccount> recipients = new ArrayList<>();
        for (int i = 0; i < attemptCount; i++) {
            recipients.add(createUserAndAccount());
        }

        ExecutorService executor = Executors.newFixedThreadPool(attemptCount);
        CountDownLatch readyLatch = new CountDownLatch(attemptCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientBalanceCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < attemptCount; i++) {
            UUID recipientUserId = recipients.get(i).userId();
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    transferService.createTransfer(sender.userId(), "concurrency-key-" + UUID.randomUUID(),
                            new CreateTransferRequest(recipientUserId, amountPerTransfer));
                    successCount.incrementAndGet();
                } catch (InsufficientBalanceException e) {
                    insufficientBalanceCount.incrementAndGet();
                } catch (TransferConflictException e) {
                    // Retries exhausted under contention is an acceptable outcome of
                    // bounded retry (never a lost update) — just not the common case.
                    conflictCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(successCount.get()).isEqualTo(affordableCount);
        assertThat(successCount.get() + insufficientBalanceCount.get() + conflictCount.get()).isEqualTo(attemptCount);

        long expectedRemaining = 100_000 - (successCount.get() * amountPerTransfer);
        assertThat(balanceOf(sender.accountId())).isEqualTo(expectedRemaining);
        assertThat(balanceOf(sender.accountId())).isGreaterThanOrEqualTo(0);

        long totalCreditedToRecipients = recipients.stream().mapToLong(r -> balanceOf(r.accountId())).sum();
        assertThat(totalCreditedToRecipients).isEqualTo(successCount.get() * amountPerTransfer);
    }

    /**
     * Every thread here submits the exact SAME {@code Idempotency-Key} and
     * the exact same request body — a genuine concurrent duplicate, e.g. a
     * client's retry-on-timeout firing while the original is still being
     * processed. Exactly one of them may actually move money; every other
     * thread must either see the same, already-completed transfer (replay)
     * or a 409 "still in progress" — never a second, independent transfer.
     */
    @Test
    void concurrentRequestsWithTheSameIdempotencyKeyMoveMoneyExactlyOnce() throws Exception {
        UserAndAccount sender = createUserAndAccount();
        UserAndAccount recipient = createUserAndAccount();
        fund(sender.accountId(), 100_000);
        long amount = 30_000;
        String sharedIdempotencyKey = "shared-key-" + UUID.randomUUID();
        int threadCount = 6;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger inProgressConflictCount = new AtomicInteger();
        Set<UUID> distinctTransferIds = ConcurrentHashMap.newKeySet();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    TransferOutcome outcome = transferService.createTransfer(sender.userId(), sharedIdempotencyKey,
                            new CreateTransferRequest(recipient.userId(), amount));
                    successCount.incrementAndGet();
                    distinctTransferIds.add(outcome.response().id());
                } catch (IdempotencyRequestInProgressException e) {
                    inProgressConflictCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(successCount.get() + inProgressConflictCount.get()).isEqualTo(threadCount);
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);
        // Whether a thread got a fresh 201 or a replayed 200, it must be
        // the SAME transfer — this is the actual "moved money exactly once" proof.
        assertThat(distinctTransferIds).hasSize(1);

        assertThat(balanceOf(sender.accountId())).isEqualTo(100_000 - amount);
        assertThat(balanceOf(recipient.accountId())).isEqualTo(amount);
    }
}
