package com.portfolio.paymentsystem.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.TestPropertySource;

/**
 * The explicit concurrency proof for M6: fires many concurrent
 * {@code tryConsume} calls for the SAME (scope, user) against real
 * host-local Redis and asserts EXACTLY the configured limit is admitted —
 * never more. This is what actually exercises the Lua script's atomicity
 * (see {@code RateLimiterService}); a lost update between a naive
 * client-side {@code GET}-then-{@code INCR} would let this test over-admit.
 * <p>
 * Calls {@link RateLimiterService} directly rather than through HTTP, same
 * reasoning as {@code TransferConcurrencyIntegrationTest}: the behavior
 * under test lives entirely in that one class, and driving it directly
 * keeps the test focused on that, not on auth/servlet plumbing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "app.rate-limit.limit=15",
        "app.rate-limit.window=PT30S"
})
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
class RateLimiterConcurrencyIntegrationTest {

    @Autowired
    private RateLimiterService rateLimiterService;

    @Test
    void concurrentAttemptsForTheSameUserNeverAdmitMoreThanTheConfiguredLimit() throws Exception {
        UUID userId = UUID.randomUUID();
        int attemptCount = 40;
        int configuredLimit = 15;

        ExecutorService executor = Executors.newFixedThreadPool(attemptCount);
        CountDownLatch readyLatch = new CountDownLatch(attemptCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger();
        AtomicInteger deniedCount = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < attemptCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    RateLimitResult result = rateLimiterService.tryConsume(RateLimitScope.TRANSFER_CREATE, userId);
                    if (result.allowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        deniedCount.incrementAndGet();
                    }
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
        assertThat(allowedCount.get()).isEqualTo(configuredLimit);
        assertThat(deniedCount.get()).isEqualTo(attemptCount - configuredLimit);
    }

    /** Sanity check that two different users never share a counter, even under concurrent load. */
    @Test
    void concurrentAttemptsForDifferentUsersDoNotShareABudget() throws Exception {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        int perUserAttempts = 15;

        ExecutorService executor = Executors.newFixedThreadPool(perUserAttempts * 2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger allowedForA = new AtomicInteger();
        AtomicInteger allowedForB = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < perUserAttempts; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                if (rateLimiterService.tryConsume(RateLimitScope.TRANSFER_CREATE, userA).allowed()) {
                    allowedForA.incrementAndGet();
                }
                return null;
            }));
            futures.add(executor.submit(() -> {
                startLatch.await();
                if (rateLimiterService.tryConsume(RateLimitScope.TRANSFER_CREATE, userB).allowed()) {
                    allowedForB.incrementAndGet();
                }
                return null;
            }));
        }

        startLatch.countDown();
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(allowedForA.get()).isEqualTo(perUserAttempts);
        assertThat(allowedForB.get()).isEqualTo(perUserAttempts);
    }
}
