package com.portfolio.paymentsystem.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.paymentsystem.common.dto.MoneyResponse;
import com.portfolio.paymentsystem.common.idempotency.domain.IdempotencyRecord;
import com.portfolio.paymentsystem.common.idempotency.exception.IdempotentReplayException;
import com.portfolio.paymentsystem.common.idempotency.exception.InvalidIdempotencyKeyException;
import com.portfolio.paymentsystem.common.idempotency.service.IdempotencyCacheService;
import com.portfolio.paymentsystem.common.idempotency.service.IdempotencyCacheService.CachedOutcome;
import com.portfolio.paymentsystem.common.idempotency.service.IdempotencyService;
import com.portfolio.paymentsystem.transfer.dto.CreateTransferRequest;
import com.portfolio.paymentsystem.transfer.dto.TransferResponse;
import com.portfolio.paymentsystem.transfer.exception.InsufficientBalanceException;
import com.portfolio.paymentsystem.transfer.exception.SelfTransferException;
import com.portfolio.paymentsystem.transfer.exception.TransferConflictException;
import com.portfolio.paymentsystem.transfer.repository.TransferRepository;

/**
 * Unit tests for the orchestrator: idempotency resolution (cache, claim,
 * replay) and the bounded optimistic-lock retry loop. All collaborators
 * are mocked, so "retry" here means "call the mock again," not a real
 * database transaction — the real thing is exercised by
 * {@code TransferConcurrencyIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID RECIPIENT_ID = UUID.randomUUID();
    private static final String IDEMPOTENCY_KEY = "client-key-1";

    @Mock
    private TransferRepository transferRepository;
    @Mock
    private TransferExecutionService transferExecutionService;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private IdempotencyCacheService idempotencyCacheService;

    private TransferService transferService;
    private ObjectMapper objectMapper;
    private TransferProperties transferProperties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        transferProperties = new TransferProperties();
        transferProperties.setMaxOptimisticLockAttempts(3);
        transferService = new TransferService(
                transferRepository, transferExecutionService, idempotencyService, idempotencyCacheService,
                objectMapper, transferProperties);
    }

    private CreateTransferRequest request() {
        return new CreateTransferRequest(RECIPIENT_ID, 40_000);
    }

    private TransferResponse sampleResponse() {
        return new TransferResponse(UUID.randomUUID(), USER_ID, RECIPIENT_ID, UUID.randomUUID(), UUID.randomUUID(),
                new MoneyResponse(40_000, "INR"), "COMPLETED", Instant.now());
    }

    private void stubCacheMiss() {
        when(idempotencyCacheService.get(eq("TRANSFER"), eq(USER_ID), eq(IDEMPOTENCY_KEY), any())).thenReturn(Optional.empty());
    }

    @Test
    void freshTransferSucceedsOnFirstAttemptAndMirrorsToCache() {
        stubCacheMiss();
        IdempotencyRecord claim = IdempotencyRecord.claim(USER_ID, IDEMPOTENCY_KEY, "irrelevant-for-mock", "TRANSFER");
        when(idempotencyService.claim(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(), eq("TRANSFER"))).thenReturn(claim);
        TransferResponse response = sampleResponse();
        when(transferExecutionService.attempt(eq(claim.getId()), eq(USER_ID), eq(RECIPIENT_ID), eq(40_000L))).thenReturn(response);

        TransferOutcome outcome = transferService.createTransfer(USER_ID, IDEMPOTENCY_KEY, request());

        assertThat(outcome.replay()).isFalse();
        assertThat(outcome.response()).isEqualTo(response);
        verify(idempotencyCacheService).put(eq("TRANSFER"), eq(USER_ID), eq(IDEMPOTENCY_KEY), any(CachedOutcome.class));
        verify(idempotencyService, never()).fail(any(), anyInt(), any());
        verify(idempotencyService, never()).release(any());
    }

    @Test
    void replaysDirectlyFromRedisWithoutTouchingPostgresClaim() throws Exception {
        TransferResponse cachedResponse = sampleResponse();
        String json = objectMapper.writeValueAsString(cachedResponse);
        when(idempotencyCacheService.get(eq("TRANSFER"), eq(USER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(Optional.of(new CachedOutcome("irrelevant", 201, json)));

        TransferOutcome outcome = transferService.createTransfer(USER_ID, IDEMPOTENCY_KEY, request());

        assertThat(outcome.replay()).isTrue();
        assertThat(outcome.response().id()).isEqualTo(cachedResponse.id());
        verifyNoInteractions(idempotencyService, transferExecutionService);
    }

    @Test
    void replaysFromPostgresWhenTheClaimIsAlreadyCompleted() throws Exception {
        stubCacheMiss();
        TransferResponse storedResponse = sampleResponse();
        IdempotencyRecord completed = IdempotencyRecord.claim(USER_ID, IDEMPOTENCY_KEY, "hash", "TRANSFER");
        completed.markCompleted(UUID.randomUUID(), 201, objectMapper.writeValueAsString(storedResponse));
        when(idempotencyService.claim(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(), eq("TRANSFER"))).thenReturn(completed);

        TransferOutcome outcome = transferService.createTransfer(USER_ID, IDEMPOTENCY_KEY, request());

        assertThat(outcome.replay()).isTrue();
        assertThat(outcome.response()).isEqualTo(storedResponse);
        verifyNoInteractions(transferExecutionService);
    }

    @Test
    void throwsIdempotentReplayForAPreviouslyFailedClaim() {
        stubCacheMiss();
        IdempotencyRecord failed = IdempotencyRecord.claim(USER_ID, IDEMPOTENCY_KEY, "hash", "TRANSFER");
        failed.markFailed(400, "{\"code\":\"INSUFFICIENT_BALANCE\"}");
        when(idempotencyService.claim(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(), eq("TRANSFER"))).thenReturn(failed);

        assertThatThrownBy(() -> transferService.createTransfer(USER_ID, IDEMPOTENCY_KEY, request()))
                .isInstanceOf(IdempotentReplayException.class)
                .satisfies(ex -> assertThat(((IdempotentReplayException) ex).getHttpStatus()).isEqualTo(400));
        verifyNoInteractions(transferExecutionService);
    }

    @Test
    void retriesOnOptimisticLockConflictAndSucceedsOnASubsequentAttempt() {
        stubCacheMiss();
        IdempotencyRecord claim = IdempotencyRecord.claim(USER_ID, IDEMPOTENCY_KEY, "hash", "TRANSFER");
        when(idempotencyService.claim(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(), eq("TRANSFER"))).thenReturn(claim);
        TransferResponse response = sampleResponse();
        when(transferExecutionService.attempt(eq(claim.getId()), eq(USER_ID), eq(RECIPIENT_ID), eq(40_000L)))
                .thenThrow(new ObjectOptimisticLockingFailureException("Account", claim.getId()))
                .thenReturn(response);

        TransferOutcome outcome = transferService.createTransfer(USER_ID, IDEMPOTENCY_KEY, request());

        assertThat(outcome.replay()).isFalse();
        assertThat(outcome.response()).isEqualTo(response);
        verify(transferExecutionService, times(2)).attempt(any(), any(), any(), anyLong());
        verify(idempotencyService, never()).release(any());
        verify(idempotencyService, never()).fail(any(), anyInt(), any());
    }

    @Test
    void releasesTheClaimAndFailsWithConflictWhenRetriesAreExhausted() {
        transferProperties.setMaxOptimisticLockAttempts(2);
        stubCacheMiss();
        IdempotencyRecord claim = IdempotencyRecord.claim(USER_ID, IDEMPOTENCY_KEY, "hash", "TRANSFER");
        when(idempotencyService.claim(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(), eq("TRANSFER"))).thenReturn(claim);
        when(transferExecutionService.attempt(any(), any(), any(), anyLong()))
                .thenThrow(new ObjectOptimisticLockingFailureException("Account", claim.getId()));

        assertThatThrownBy(() -> transferService.createTransfer(USER_ID, IDEMPOTENCY_KEY, request()))
                .isInstanceOf(TransferConflictException.class);

        verify(transferExecutionService, times(2)).attempt(any(), any(), any(), anyLong());
        verify(idempotencyService).release(claim.getId());
        verify(idempotencyCacheService, never()).put(any(), any(), any(), any());
    }

    @Test
    void cachesABusinessFailureAndRethrowsIt() {
        stubCacheMiss();
        IdempotencyRecord claim = IdempotencyRecord.claim(USER_ID, IDEMPOTENCY_KEY, "hash", "TRANSFER");
        when(idempotencyService.claim(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(), eq("TRANSFER"))).thenReturn(claim);
        InsufficientBalanceException failure = new InsufficientBalanceException(UUID.randomUUID(), 1_000, 40_000);
        when(transferExecutionService.attempt(any(), any(), any(), anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> transferService.createTransfer(USER_ID, IDEMPOTENCY_KEY, request()))
                .isSameAs(failure);

        verify(idempotencyService).fail(eq(claim.getId()), eq(400), any());
        verify(idempotencyService, never()).release(any());
    }

    @Test
    void releasesTheClaimOnAnUnexpectedException() {
        stubCacheMiss();
        IdempotencyRecord claim = IdempotencyRecord.claim(USER_ID, IDEMPOTENCY_KEY, "hash", "TRANSFER");
        when(idempotencyService.claim(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(), eq("TRANSFER"))).thenReturn(claim);
        RuntimeException boom = new RuntimeException("database is on fire");
        when(transferExecutionService.attempt(any(), any(), any(), anyLong())).thenThrow(boom);

        assertThatThrownBy(() -> transferService.createTransfer(USER_ID, IDEMPOTENCY_KEY, request()))
                .isSameAs(boom);

        verify(idempotencyService).release(claim.getId());
        verify(idempotencyService, never()).fail(any(), anyInt(), any());
    }

    @Test
    void rejectsSelfTransferBeforeTouchingIdempotencyMachinery() {
        CreateTransferRequest selfRequest = new CreateTransferRequest(USER_ID, 1_000);

        assertThatThrownBy(() -> transferService.createTransfer(USER_ID, IDEMPOTENCY_KEY, selfRequest))
                .isInstanceOf(SelfTransferException.class);

        verifyNoInteractions(idempotencyCacheService, idempotencyService, transferExecutionService);
    }

    @Test
    void rejectsABlankIdempotencyKeyBeforeTouchingIdempotencyMachinery() {
        assertThatThrownBy(() -> transferService.createTransfer(USER_ID, "   ", request()))
                .isInstanceOf(InvalidIdempotencyKeyException.class);

        verifyNoInteractions(idempotencyCacheService, idempotencyService, transferExecutionService);
    }
}
