package com.portfolio.paymentsystem.common.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentsystem.common.idempotency.domain.IdempotencyRecord;
import com.portfolio.paymentsystem.common.idempotency.domain.IdempotencyStatus;
import com.portfolio.paymentsystem.common.idempotency.exception.IdempotencyKeyReusedException;
import com.portfolio.paymentsystem.common.idempotency.exception.IdempotencyRequestInProgressException;
import com.portfolio.paymentsystem.common.idempotency.repository.IdempotencyRecordRepository;

/**
 * Unit tests for {@link IdempotencyService}'s claim resolution logic: the
 * heart of "PostgreSQL is authoritative" — no Redis involved anywhere here.
 * {@link PlatformTransactionManager} is mocked to just invoke the callback
 * transparently (no real transaction machinery) — the two-transaction
 * split it enables is instead proven for real by
 * {@code TransferConcurrencyIntegrationTest} against actual PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String KEY = "client-key-1";
    private static final String HASH = "hash-abc";

    @Mock
    private IdempotencyRecordRepository repository;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        idempotencyService = new IdempotencyService(repository, new ObjectMapper(), transactionManager);
    }

    @Test
    void claimsANewKeyWhenNoRecordExistsYet() {
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        IdempotencyRecord claimed = idempotencyService.claim(USER_ID, KEY, HASH, "TRANSFER");

        assertThat(claimed.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(claimed.getUserId()).isEqualTo(USER_ID);
        assertThat(claimed.getRequestHash()).isEqualTo(HASH);
    }

    @Test
    void replaysACompletedRecordWhenTheRequestHashMatches() {
        IdempotencyRecord existing = IdempotencyRecord.claim(USER_ID, KEY, HASH, "TRANSFER");
        existing.markCompleted(UUID.randomUUID(), 201, "{\"id\":\"abc\"}");
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(existing));

        IdempotencyRecord result = idempotencyService.claim(USER_ID, KEY, HASH, "TRANSFER");

        assertThat(result.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(result.getResponseBody()).isEqualTo("{\"id\":\"abc\"}");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void replaysAFailedRecordWhenTheRequestHashMatches() {
        IdempotencyRecord existing = IdempotencyRecord.claim(USER_ID, KEY, HASH, "TRANSFER");
        existing.markFailed(400, "{\"code\":\"INSUFFICIENT_BALANCE\"}");
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(existing));

        IdempotencyRecord result = idempotencyService.claim(USER_ID, KEY, HASH, "TRANSFER");

        assertThat(result.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(result.getHttpStatus()).isEqualTo(400);
    }

    @Test
    void rejectsKeyReuseWithADifferentRequestBodyRegardlessOfStatus() {
        IdempotencyRecord existing = IdempotencyRecord.claim(USER_ID, KEY, "different-hash", "TRANSFER");
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> idempotencyService.claim(USER_ID, KEY, HASH, "TRANSFER"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void rejectsAConcurrentDuplicateStillInProgress() {
        IdempotencyRecord existing = IdempotencyRecord.claim(USER_ID, KEY, HASH, "TRANSFER");
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> idempotencyService.claim(USER_ID, KEY, HASH, "TRANSFER"))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
    }

    /**
     * Simulates the real race: our own lookup sees nothing, we attempt the
     * INSERT, but a concurrent request's INSERT commits first — the unique
     * index rejects ours. We must then re-read and resolve against the
     * winner's row exactly as if we had seen it from the start.
     */
    @Test
    void resolvesAgainstTheWinnerWhenTheInsertRaceIsLost() {
        IdempotencyRecord winner = IdempotencyRecord.claim(USER_ID, KEY, HASH, "TRANSFER");
        winner.markCompleted(UUID.randomUUID(), 201, "{\"id\":\"winner\"}");

        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        IdempotencyRecord result = idempotencyService.claim(USER_ID, KEY, HASH, "TRANSFER");

        assertThat(result.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(result.getResponseBody()).isEqualTo("{\"id\":\"winner\"}");
        verify(repository, times(2)).findByUserIdAndIdempotencyKey(USER_ID, KEY);
    }

    @Test
    void completeMarksTheRecordCompletedWithSerializedBody() {
        IdempotencyRecord record = IdempotencyRecord.claim(USER_ID, KEY, HASH, "TRANSFER");
        when(repository.findById(any())).thenReturn(Optional.of(record));
        UUID resourceId = UUID.randomUUID();

        idempotencyService.complete(record.getId(), resourceId, 201, new SampleBody("ok"));

        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getResourceId()).isEqualTo(resourceId);
        assertThat(record.getHttpStatus()).isEqualTo(201);
        assertThat(record.getResponseBody()).contains("\"value\":\"ok\"");
    }

    @Test
    void failMarksTheRecordFailedWithSerializedBody() {
        IdempotencyRecord record = IdempotencyRecord.claim(USER_ID, KEY, HASH, "TRANSFER");
        when(repository.findById(any())).thenReturn(Optional.of(record));

        idempotencyService.fail(record.getId(), 400, new SampleBody("insufficient"));

        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(record.getHttpStatus()).isEqualTo(400);
    }

    @Test
    void releaseDeletesTheClaim() {
        UUID recordId = UUID.randomUUID();

        idempotencyService.release(recordId);

        verify(repository).deleteById(recordId);
    }

    @Test
    void readBodyDeserializesStoredJson() {
        SampleBody body = idempotencyService.readBody("{\"value\":\"ok\"}", SampleBody.class);

        assertThat(body.value()).isEqualTo("ok");
    }

    private record SampleBody(String value) {
    }
}
