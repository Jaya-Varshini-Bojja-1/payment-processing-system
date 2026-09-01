package com.portfolio.paymentsystem.payment.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

import com.portfolio.paymentsystem.common.domain.Currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A gateway-backed deposit or withdrawal. Unlike {@code Transfer}, this row
 * is genuinely observed in a non-terminal state ({@code PENDING} /
 * {@code PROCESSING} / {@code RETRYING}) between creation and outcome — the
 * gateway call is real (simulated) I/O on the other side of a Kafka hop,
 * not a same-transaction ledger post.
 * <p>
 * No ledger post is ever made until this reaches {@link PaymentStatus#SUCCEEDED}
 * (see {@code payment.service.PaymentOutcomeService}). Every other terminal
 * state means no money moved, so there is nothing to compensate/reverse.
 * <p>
 * Implements {@link Persistable} for the same reason as {@code Transfer}:
 * an application-assigned id with no {@code @Version} column would
 * otherwise route the first {@code save()} through {@code merge()} instead
 * of {@code persist()}.
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private PaymentType type;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentStatus status;

    @Column(name = "gateway_reference", length = 64)
    private String gatewayReference;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentTransaction() {
        // JPA
    }

    public static PaymentTransaction initiate(UUID userId, UUID accountId, PaymentType type, long amountPaise, Currency currency) {
        PaymentTransaction payment = new PaymentTransaction();
        payment.id = UUID.randomUUID();
        payment.userId = userId;
        payment.accountId = accountId;
        payment.type = type;
        payment.amountPaise = amountPaise;
        payment.currency = currency;
        payment.status = PaymentStatus.PENDING;
        payment.attemptCount = 0;
        return payment;
    }

    /** Allowed from {@code PENDING} or {@code RETRYING}, right before the consumer calls the gateway. */
    public void markProcessing() {
        this.status = PaymentStatus.PROCESSING;
    }

    public void markSucceeded(String gatewayReference) {
        this.status = PaymentStatus.SUCCEEDED;
        this.gatewayReference = gatewayReference;
    }

    /** Terminal, non-retryable: a decline, or an insufficient-balance re-check failure at settlement. */
    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = truncate(reason);
    }

    /** Transient failure with attempts remaining: bumps {@link #attemptCount} so the next attempt's request reflects it. */
    public void scheduleRetry(String reason) {
        this.status = PaymentStatus.RETRYING;
        this.attemptCount += 1;
        this.failureReason = truncate(reason);
    }

    /** Retries exhausted: terminal, surfaced to ops via the payments DLQ topic (see {@code PaymentEventPublisher#publishExhausted}). */
    public void markRequiresManualReview(String reason) {
        this.status = PaymentStatus.REQUIRES_MANUAL_REVIEW;
        this.attemptCount += 1;
        this.failureReason = truncate(reason);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public PaymentType getType() {
        return type;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public Currency getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
