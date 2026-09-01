package com.portfolio.paymentsystem.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.paymentsystem.common.dto.MoneyResponse;
import com.portfolio.paymentsystem.common.idempotency.domain.IdempotencyRecord;
import com.portfolio.paymentsystem.common.idempotency.exception.IdempotentReplayException;
import com.portfolio.paymentsystem.common.idempotency.exception.InvalidIdempotencyKeyException;
import com.portfolio.paymentsystem.common.idempotency.service.IdempotencyCacheService;
import com.portfolio.paymentsystem.common.idempotency.service.IdempotencyCacheService.CachedOutcome;
import com.portfolio.paymentsystem.common.idempotency.service.IdempotencyService;
import com.portfolio.paymentsystem.payment.dto.CreatePaymentRequest;
import com.portfolio.paymentsystem.payment.dto.PaymentResponse;
import com.portfolio.paymentsystem.payment.exception.InsufficientBalanceException;
import com.portfolio.paymentsystem.payment.repository.PaymentTransactionRepository;

/**
 * Unit tests for the orchestrator: idempotency resolution (cache, claim,
 * replay). Mirrors {@code TransferServiceTest}, minus the optimistic-lock
 * retry-loop tests -- {@code PaymentExecutionService} has no such retry.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String IDEMPOTENCY_KEY = "client-key-1";

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private PaymentExecutionService paymentExecutionService;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private IdempotencyCacheService idempotencyCacheService;

    private PaymentService paymentService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        paymentService = new PaymentService(
                paymentTransactionRepository, paymentExecutionService, idempotencyService, idempotencyCacheService, objectMapper);
    }

    private CreatePaymentRequest request() {
        return new CreatePaymentRequest(40_000);
    }

    private PaymentResponse sampleResponse() {
        return new PaymentResponse(UUID.randomUUID(), USER_ID, UUID.randomUUID(), "DEPOSIT",
                new MoneyResponse(40_000, "INR"), "PENDING", null, 0, null, Instant.now());
    }

    private void stubCacheMiss() {
        when(idempotencyCacheService.get(eq("PAYMENT_TRANSACTION"), eq(USER_ID), eq(IDEMPOTENCY_KEY), any())).thenReturn(Optional.empty());
    }

    @Test
    void freshDepositSucceedsAndMirrorsToCache() {
        stubCacheMiss();
        IdempotencyRecord claim = IdempotencyRecord.claim(USER_ID, IDEMPOTENCY_KEY, "irrelevant-for-mock", "PAYMENT_TRANSACTION");
        when(idempotencyService.claim(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(), eq("PAYMENT_TRANSACTION"))).thenReturn(claim);
        PaymentResponse response = sampleResponse();
        when(paymentExecutionService.attempt(eq(claim.getId()), eq(USER_ID), any(), eq(40_000L))).thenReturn(response);

        PaymentOutcome outcome = paymentService.createDeposit(USER_ID, IDEMPOTENCY_KEY, request());

        assertThat(outcome.replay()).isFalse();
        assertThat(outcome.response()).isEqualTo(response);
        verify(idempotencyCacheService).put(eq("PAYMENT_TRANSACTION"), eq(USER_ID), eq(IDEMPOTENCY_KEY), any(CachedOutcome.class));
        verify(idempotencyService, never()).fail(any(), anyInt(), any());
        verify(idempotencyService, never()).release(any());
    }

    @Test
    void replaysDirectlyFromRedisWithoutTouchingPostgresClaim() throws Exception {
        PaymentResponse cachedResponse = sampleResponse();
        String json = objectMapper.writeValueAsString(cachedResponse);
        when(idempotencyCacheService.get(eq("PAYMENT_TRANSACTION"), eq(USER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(Optional.of(new CachedOutcome("irrelevant", 202, json)));

        PaymentOutcome outcome = paymentService.createWithdrawal(USER_ID, IDEMPOTENCY_KEY, request());

        assertThat(outcome.replay()).isTrue();
        assertThat(outcome.response().id()).isEqualTo(cachedResponse.id());
        verifyNoInteractions(idempotencyService, paymentExecutionService);
    }

    @Test
    void replaysFromPostgresWhenTheClaimIsAlreadyCompleted() throws Exception {
        stubCacheMiss();
        PaymentResponse storedResponse = sampleResponse();
        IdempotencyRecord completed = IdempotencyRecord.claim(USER_ID, IDEMPOTENCY_KEY, "hash", "PAYMENT_TRANSACTION");
        completed.markCompleted(UUID.randomUUID(), 202, objectMapper.writeValueAsString(storedResponse));
        when(idempotencyService.claim(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(), eq("PAYMENT_TRANSACTION"))).thenReturn(completed);

        PaymentOutcome outcome = paymentService.createDeposit(USER_ID, IDEMPOTENCY_KEY, request());

        assertThat(outcome.replay()).isTrue();
        assertThat(outcome.response()).isEqualTo(storedResponse);
        verifyNoInteractions(paymentExecutionService);
    }

    @Test
    void throwsIdempotentReplayForAPreviouslyFailedClaim() {
        stubCacheMiss();
        IdempotencyRecord failed = IdempotencyRecord.claim(USER_ID, IDEMPOTENCY_KEY, "hash", "PAYMENT_TRANSACTION");
        failed.markFailed(400, "{\"code\":\"INSUFFICIENT_BALANCE\"}");
        when(idempotencyService.claim(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(), eq("PAYMENT_TRANSACTION"))).thenReturn(failed);

        assertThatThrownBy(() -> paymentService.createWithdrawal(USER_ID, IDEMPOTENCY_KEY, request()))
                .isInstanceOf(IdempotentReplayException.class)
                .satisfies(ex -> assertThat(((IdempotentReplayException) ex).getHttpStatus()).isEqualTo(400));
        verifyNoInteractions(paymentExecutionService);
    }

    @Test
    void cachesABusinessFailureAndRethrowsIt() {
        stubCacheMiss();
        IdempotencyRecord claim = IdempotencyRecord.claim(USER_ID, IDEMPOTENCY_KEY, "hash", "PAYMENT_TRANSACTION");
        when(idempotencyService.claim(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(), eq("PAYMENT_TRANSACTION"))).thenReturn(claim);
        InsufficientBalanceException failure = new InsufficientBalanceException(UUID.randomUUID(), 1_000, 40_000);
        when(paymentExecutionService.attempt(any(), any(), any(), anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> paymentService.createWithdrawal(USER_ID, IDEMPOTENCY_KEY, request()))
                .isSameAs(failure);

        verify(idempotencyService).fail(eq(claim.getId()), eq(400), any());
        verify(idempotencyService, never()).release(any());
    }

    @Test
    void releasesTheClaimOnAnUnexpectedException() {
        stubCacheMiss();
        IdempotencyRecord claim = IdempotencyRecord.claim(USER_ID, IDEMPOTENCY_KEY, "hash", "PAYMENT_TRANSACTION");
        when(idempotencyService.claim(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(), eq("PAYMENT_TRANSACTION"))).thenReturn(claim);
        RuntimeException boom = new RuntimeException("database is on fire");
        when(paymentExecutionService.attempt(any(), any(), any(), anyLong())).thenThrow(boom);

        assertThatThrownBy(() -> paymentService.createDeposit(USER_ID, IDEMPOTENCY_KEY, request()))
                .isSameAs(boom);

        verify(idempotencyService).release(claim.getId());
        verify(idempotencyService, never()).fail(any(), anyInt(), any());
    }

    @Test
    void rejectsABlankIdempotencyKeyBeforeTouchingIdempotencyMachinery() {
        assertThatThrownBy(() -> paymentService.createDeposit(USER_ID, "   ", request()))
                .isInstanceOf(InvalidIdempotencyKeyException.class);

        verifyNoInteractions(idempotencyCacheService, idempotencyService, paymentExecutionService);
    }
}
