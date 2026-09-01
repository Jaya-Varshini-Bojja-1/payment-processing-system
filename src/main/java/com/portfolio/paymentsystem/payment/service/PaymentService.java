package com.portfolio.paymentsystem.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
import com.portfolio.paymentsystem.payment.domain.PaymentStatus;
import com.portfolio.paymentsystem.payment.domain.PaymentType;
import com.portfolio.paymentsystem.payment.dto.CreatePaymentRequest;
import com.portfolio.paymentsystem.payment.dto.PaymentResponse;
import com.portfolio.paymentsystem.payment.exception.PaymentAccessDeniedException;
import com.portfolio.paymentsystem.payment.exception.PaymentNotFoundException;
import com.portfolio.paymentsystem.payment.repository.PaymentTransactionRepository;

/**
 * The deposit/withdrawal entry point: idempotency (Redis fast path, then
 * PostgreSQL as authority) around one call to
 * {@link PaymentExecutionService#attempt}. Mirrors
 * {@code transfer.service.TransferService}, minus the optimistic-lock retry
 * loop -- {@link PaymentExecutionService#attempt} never writes to
 * {@code Account}, so there is no version conflict to retry here.
 */
@Service
public class PaymentService {

    private static final String RESOURCE_TYPE = "PAYMENT_TRANSACTION";

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentExecutionService paymentExecutionService;
    private final IdempotencyService idempotencyService;
    private final IdempotencyCacheService idempotencyCacheService;
    private final ObjectMapper objectMapper;

    public PaymentService(
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentExecutionService paymentExecutionService,
            IdempotencyService idempotencyService,
            IdempotencyCacheService idempotencyCacheService,
            ObjectMapper objectMapper) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentExecutionService = paymentExecutionService;
        this.idempotencyService = idempotencyService;
        this.idempotencyCacheService = idempotencyCacheService;
        this.objectMapper = objectMapper;
    }

    public PaymentOutcome createDeposit(UUID userId, String idempotencyKey, CreatePaymentRequest request) {
        return create(userId, idempotencyKey, request, PaymentType.DEPOSIT);
    }

    public PaymentOutcome createWithdrawal(UUID userId, String idempotencyKey, CreatePaymentRequest request) {
        return create(userId, idempotencyKey, request, PaymentType.WITHDRAWAL);
    }

    private PaymentOutcome create(UUID userId, String idempotencyKey, CreatePaymentRequest request, PaymentType type) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException();
        }

        String requestHash = hashRequest(type, request);

        Optional<CachedOutcome> cached = idempotencyCacheService.get(RESOURCE_TYPE, userId, idempotencyKey, requestHash);
        if (cached.isPresent()) {
            return replay(cached.get().httpStatus(), cached.get().responseBody());
        }

        IdempotencyRecord claim = idempotencyService.claim(userId, idempotencyKey, requestHash, RESOURCE_TYPE);
        if (claim.getStatus() != IdempotencyStatus.IN_PROGRESS) {
            return replay(claim.getHttpStatus(), claim.getResponseBody());
        }

        try {
            PaymentResponse response = paymentExecutionService.attempt(claim.getId(), userId, type, request.amountPaise());
            idempotencyCacheService.put(RESOURCE_TYPE, userId, idempotencyKey,
                    new CachedOutcome(requestHash, HttpStatus.ACCEPTED.value(), writeJson(response)));
            return PaymentOutcome.fresh(response);
        } catch (ApiException businessFailure) {
            idempotencyService.fail(claim.getId(), businessFailure.getStatus().value(), ProblemDetailFactory.from(businessFailure));
            throw businessFailure;
        } catch (RuntimeException unexpected) {
            // Not a deterministic business outcome -- release the claim so the
            // same Idempotency-Key can be used for a genuine subsequent attempt
            // rather than being permanently poisoned by an infrastructure error.
            idempotencyService.release(claim.getId());
            throw unexpected;
        }
    }

    private PaymentOutcome replay(Integer httpStatus, String responseBodyJson) {
        if (httpStatus != null && httpStatus >= 200 && httpStatus < 300) {
            return PaymentOutcome.replay(readJson(responseBodyJson, PaymentResponse.class));
        }
        Object body = readJson(responseBodyJson, Object.class);
        throw new IdempotentReplayException(httpStatus == null ? HttpStatus.INTERNAL_SERVER_ERROR.value() : httpStatus, body);
    }

    private String hashRequest(PaymentType type, CreatePaymentRequest request) {
        String canonical = type + ":" + request.amountPaise();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt idempotent response body", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize payment response for caching", e);
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId, UUID callerUserId) {
        var payment = paymentTransactionRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (!payment.getUserId().equals(callerUserId)) {
            throw new PaymentAccessDeniedException(paymentId);
        }
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> listPayments(UUID callerUserId, PaymentType type, PaymentStatus status, Pageable pageable) {
        return paymentTransactionRepository.findForUser(callerUserId, type, status, pageable).map(PaymentResponse::from);
    }
}
