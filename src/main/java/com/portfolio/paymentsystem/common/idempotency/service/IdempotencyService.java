package com.portfolio.paymentsystem.common.idempotency.service;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentsystem.common.idempotency.domain.IdempotencyRecord;
import com.portfolio.paymentsystem.common.idempotency.domain.IdempotencyStatus;
import com.portfolio.paymentsystem.common.idempotency.exception.IdempotencyKeyReusedException;
import com.portfolio.paymentsystem.common.idempotency.exception.IdempotencyRequestInProgressException;
import com.portfolio.paymentsystem.common.idempotency.repository.IdempotencyRecordRepository;

/**
 * PostgreSQL is the <b>only</b> authority this class trusts. Every method
 * here is safe to use even if Redis (see {@code IdempotencyCacheService})
 * is completely absent from the system — Redis is layered on top by
 * {@code TransferService}, never consulted here.
 *
 * <h2>Why {@link #claim} either throws or hands back a safe-to-use record</h2>
 * Callers never need to separately ask "is someone else already handling
 * this key?" — {@link #claim} resolves that question itself:
 * <ul>
 *   <li>Returns a record with {@link IdempotencyStatus#IN_PROGRESS} only
 *       when <b>this call</b> just created it — safe for the caller to go
 *       do the work.</li>
 *   <li>Returns a record with {@link IdempotencyStatus#COMPLETED} or
 *       {@link IdempotencyStatus#FAILED} when a matching prior attempt
 *       already reached a terminal outcome — the caller replays it.</li>
 *   <li>Throws {@link IdempotencyRequestInProgressException} if some other
 *       in-flight request already owns this key.</li>
 *   <li>Throws {@link IdempotencyKeyReusedException} if the key was reused
 *       with a different request body, regardless of the existing record's
 *       status.</li>
 * </ul>
 *
 * <h2>Why {@link #claim} is two separate transactions, not one</h2>
 * PostgreSQL aborts an <b>entire</b> transaction the instant any statement
 * inside it errors — every later statement on that same connection fails
 * with "current transaction is aborted" until a real {@code ROLLBACK}
 * happens, even a harmless {@code SELECT}. Catching the unique-constraint
 * violation from a losing INSERT and then querying for the winner
 * <b>inside that same transaction</b> (as an initial version of this class
 * did) therefore does not work: the fallback query itself fails. Each
 * phase below runs in its own independent {@code REQUIRES_NEW}
 * transaction instead, so a failed INSERT's transaction is fully rolled
 * back and closed before the fallback read ever starts. This is safe
 * because the two phases were never a single atomic unit to begin with —
 * the database's unique index, not application-level compare-and-swap, is
 * what actually arbitrates the race.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTransaction;

    public IdempotencyService(IdempotencyRecordRepository repository, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public IdempotencyRecord claim(UUID userId, String idempotencyKey, String requestHash, String resourceType) {
        Optional<IdempotencyRecord> existing = findExisting(userId, idempotencyKey);
        if (existing.isPresent()) {
            return requireOwnableOrTerminal(existing.get(), requestHash);
        }
        try {
            return insertClaim(userId, idempotencyKey, requestHash, resourceType);
        } catch (DataIntegrityViolationException e) {
            // Lost the INSERT race: some other request's claim committed between our
            // findExisting lookup and our own INSERT's (now separately rolled-back)
            // transaction. Its row is now visible in a fresh transaction — resolve
            // exactly as if we had found it up front.
            IdempotencyRecord winner = findExisting(userId, idempotencyKey).orElseThrow(() -> e);
            return requireOwnableOrTerminal(winner, requestHash);
        }
    }

    private Optional<IdempotencyRecord> findExisting(UUID userId, String idempotencyKey) {
        return requiresNewTransaction.execute(status -> repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey));
    }

    private IdempotencyRecord insertClaim(UUID userId, String idempotencyKey, String requestHash, String resourceType) {
        return requiresNewTransaction.execute(status -> {
            IdempotencyRecord record = IdempotencyRecord.claim(userId, idempotencyKey, requestHash, resourceType);
            // Flushed (rather than left for TransactionTemplate's own commit) purely
            // so the UNIQUE(user_id, idempotency_key) violation surfaces here, inside
            // this try block, with the transaction still cleanly attributable to it.
            repository.saveAndFlush(record);
            return record;
        });
    }

    private IdempotencyRecord requireOwnableOrTerminal(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(record.getIdempotencyKey());
        }
        if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            throw new IdempotencyRequestInProgressException(record.getIdempotencyKey());
        }
        return record;
    }

    /**
     * Records a successful terminal outcome. Deliberately has no
     * {@code REQUIRES_NEW}: called from within the same attempt transaction
     * that just posted the ledger entries (see
     * {@code TransferExecutionService}), so it commits atomically with the
     * money movement it describes — either both happen, or (on later
     * rollback, e.g. an optimistic-lock failure later in that transaction)
     * neither does, leaving the claim retryable.
     */
    @Transactional
    public void complete(UUID recordId, UUID resourceId, int httpStatus, Object responseBody) {
        IdempotencyRecord record = require(recordId);
        record.markCompleted(resourceId, httpStatus, writeJson(responseBody));
    }

    /**
     * Records a deterministic business failure (e.g. insufficient balance)
     * as a terminal, replayable outcome. Called by the orchestrator
     * ({@code TransferService}) <b>after</b> the failed attempt's own
     * transaction has already rolled back, so this always runs in its own
     * fresh transaction.
     */
    @Transactional
    public void fail(UUID recordId, int httpStatus, Object responseBody) {
        IdempotencyRecord record = require(recordId);
        record.markFailed(httpStatus, writeJson(responseBody));
    }

    /**
     * Releases a claim that must not be treated as a terminal outcome:
     * either an unexpected/infrastructure error, or optimistic-lock
     * retries were exhausted under contention. Deleting (rather than
     * marking {@code FAILED}) means the key is immediately available again
     * for a genuine retry — no expiry/reaper job needed for M3's scope.
     */
    @Transactional
    public void release(UUID recordId) {
        repository.deleteById(recordId);
    }

    public <T> T readBody(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt idempotency response body", e);
        }
    }

    private IdempotencyRecord require(UUID recordId) {
        return repository.findById(recordId)
                .orElseThrow(() -> new NoSuchElementException("Idempotency record '" + recordId + "' does not exist"));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotent response body", e);
        }
    }
}
