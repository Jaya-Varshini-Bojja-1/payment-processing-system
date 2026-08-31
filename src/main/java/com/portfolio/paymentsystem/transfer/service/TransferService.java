package com.portfolio.paymentsystem.transfer.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentsystem.common.exception.ApiException;
import com.portfolio.paymentsystem.common.exception.ProblemDetailFactory;
import com.portfolio.paymentsystem.common.idempotency.domain.IdempotencyRecord;
import com.portfolio.paymentsystem.common.idempotency.domain.IdempotencyStatus;
import com.portfolio.paymentsystem.common.idempotency.exception.IdempotentReplayException;
import com.portfolio.paymentsystem.common.idempotency.exception.InvalidIdempotencyKeyException;
import com.portfolio.paymentsystem.common.idempotency.service.IdempotencyCacheService;
import com.portfolio.paymentsystem.common.idempotency.service.IdempotencyCacheService.CachedOutcome;
import com.portfolio.paymentsystem.common.idempotency.service.IdempotencyService;
import com.portfolio.paymentsystem.transfer.domain.TransferStatus;
import com.portfolio.paymentsystem.transfer.dto.CreateTransferRequest;
import com.portfolio.paymentsystem.transfer.dto.TransferResponse;
import com.portfolio.paymentsystem.transfer.exception.SelfTransferException;
import com.portfolio.paymentsystem.transfer.exception.TransferAccessDeniedException;
import com.portfolio.paymentsystem.transfer.exception.TransferConflictException;
import com.portfolio.paymentsystem.transfer.exception.TransferNotFoundException;
import com.portfolio.paymentsystem.transfer.repository.TransferRepository;

/**
 * The P2P transfer entry point: input validation, idempotency (Redis fast
 * path, then PostgreSQL as authority), and the bounded optimistic-lock
 * retry loop around {@link TransferExecutionService}.
 *
 * <h2>Why this class is not {@code @Transactional}</h2>
 * It deliberately orchestrates <b>several independent transactions</b>:
 * the idempotency claim commits on its own before any money moves; each
 * {@link TransferExecutionService#attempt} call is its own transaction;
 * and {@code fail}/{@code release} bookkeeping after a failed attempt runs
 * in yet another fresh transaction. If this method carried
 * {@code @Transactional}, every one of those calls would instead join one
 * shared transaction — an exception from a failed attempt would mark that
 * whole transaction rollback-only, making it impossible to then commit a
 * "this key failed, cache that" record afterwards in the same request.
 *
 * <h2>Why retries happen here, not in {@code LedgerService}</h2>
 * A version conflict means the world changed since we last read it. The
 * only safe response is to re-read and re-decide from scratch — re-check
 * "is there still enough balance?", not blindly reapply the same debit.
 * That is a business decision about what a transfer means, which is why
 * it belongs to this orchestrator and not to the generic ledger writer.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final String RESOURCE_TYPE = "TRANSFER";

    private final TransferRepository transferRepository;
    private final TransferExecutionService transferExecutionService;
    private final IdempotencyService idempotencyService;
    private final IdempotencyCacheService idempotencyCacheService;
    private final ObjectMapper objectMapper;
    private final TransferProperties transferProperties;

    public TransferService(
            TransferRepository transferRepository,
            TransferExecutionService transferExecutionService,
            IdempotencyService idempotencyService,
            IdempotencyCacheService idempotencyCacheService,
            ObjectMapper objectMapper,
            TransferProperties transferProperties) {
        this.transferRepository = transferRepository;
        this.transferExecutionService = transferExecutionService;
        this.idempotencyService = idempotencyService;
        this.idempotencyCacheService = idempotencyCacheService;
        this.objectMapper = objectMapper;
        this.transferProperties = transferProperties;
    }

    public TransferOutcome createTransfer(UUID userId, String idempotencyKey, CreateTransferRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException();
        }
        if (userId.equals(request.toUserId())) {
            throw new SelfTransferException();
        }

        String requestHash = hashRequest(request);

        Optional<CachedOutcome> cached = idempotencyCacheService.get(RESOURCE_TYPE, userId, idempotencyKey, requestHash);
        if (cached.isPresent()) {
            return replay(cached.get().httpStatus(), cached.get().responseBody());
        }

        IdempotencyRecord claim = idempotencyService.claim(userId, idempotencyKey, requestHash, RESOURCE_TYPE);
        if (claim.getStatus() != IdempotencyStatus.IN_PROGRESS) {
            return replay(claim.getHttpStatus(), claim.getResponseBody());
        }

        TransferOutcome outcome = executeWithRetry(claim.getId(), userId, request);

        idempotencyCacheService.put(RESOURCE_TYPE, userId, idempotencyKey,
                new CachedOutcome(requestHash, HttpStatus.CREATED.value(), writeJson(outcome.response())));
        return outcome;
    }

    private TransferOutcome executeWithRetry(UUID claimId, UUID userId, CreateTransferRequest request) {
        int maxAttempts = transferProperties.getMaxOptimisticLockAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                TransferResponse response = transferExecutionService.attempt(claimId, userId, request.toUserId(), request.amountPaise());
                return TransferOutcome.fresh(response);
            } catch (ObjectOptimisticLockingFailureException conflict) {
                if (attempt == maxAttempts) {
                    idempotencyService.release(claimId);
                    throw new TransferConflictException(attempt);
                }
                log.info("Transfer attempt {}/{} lost an optimistic-lock race on an account version; "
                        + "retrying with a fresh read of both accounts", attempt, maxAttempts);
            } catch (ApiException businessFailure) {
                idempotencyService.fail(claimId, businessFailure.getStatus().value(), ProblemDetailFactory.from(businessFailure));
                throw businessFailure;
            } catch (RuntimeException unexpected) {
                // Not a deterministic business outcome — release the claim so the
                // same Idempotency-Key can be used for a genuine subsequent attempt
                // rather than being permanently poisoned by an infrastructure error.
                idempotencyService.release(claimId);
                throw unexpected;
            }
        }
        // Unreachable: the loop above always returns or throws.
        throw new IllegalStateException("Transfer retry loop exited without a result");
    }

    private TransferOutcome replay(Integer httpStatus, String responseBodyJson) {
        if (httpStatus != null && httpStatus >= 200 && httpStatus < 300) {
            return TransferOutcome.replay(readJson(responseBodyJson, TransferResponse.class));
        }
        Object body = readJson(responseBodyJson, Object.class);
        throw new IdempotentReplayException(httpStatus == null ? HttpStatus.INTERNAL_SERVER_ERROR.value() : httpStatus, body);
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt idempotent response body", e);
        }
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID transferId, UUID callerUserId) {
        var transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
        requireParty(transfer.getInitiatorUserId(), transfer.getDestinationUserId(), callerUserId, transferId);
        return TransferResponse.from(transfer);
    }

    @Transactional(readOnly = true)
    public Page<TransferResponse> listTransfers(UUID callerUserId, TransferStatus status, Pageable pageable) {
        return transferRepository.findForUser(callerUserId, status, pageable).map(TransferResponse::from);
    }

    private void requireParty(UUID initiatorUserId, UUID destinationUserId, UUID callerUserId, UUID transferId) {
        if (!initiatorUserId.equals(callerUserId) && !destinationUserId.equals(callerUserId)) {
            throw new TransferAccessDeniedException(transferId);
        }
    }

    private String hashRequest(CreateTransferRequest request) {
        String canonical = request.toUserId() + ":" + request.amountPaise();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize transfer response for caching", e);
        }
    }
}
