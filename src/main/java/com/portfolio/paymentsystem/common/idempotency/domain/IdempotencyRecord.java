package com.portfolio.paymentsystem.common.idempotency.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

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
 * PostgreSQL's authoritative record of one client {@code Idempotency-Key}.
 * Generic on purpose: {@code resourceType} ("TRANSFER" in M3, later
 * "PAYMENT_TRANSACTION") lets every money-moving feature share this one
 * table instead of each inventing its own idempotency mechanism.
 * <p>
 * The database-level {@code UNIQUE(user_id, idempotency_key)} constraint
 * (see V4 migration) — not any in-memory lock — is what makes claiming a
 * key safe under concurrency: two simultaneous {@link #claim} attempts for
 * the same key race on that unique index, and only one INSERT can win.
 * <p>
 * Redis (see {@code IdempotencyCacheService}) never appears in this class;
 * it is a read-through cache layered in front of this table by
 * {@code IdempotencyService}, never a substitute for it.
 * <p>
 * Implements {@link Persistable} for the same reason as {@code Transfer}:
 * the id is application-assigned and there is no {@code @Version} column,
 * so without this Spring Data JPA would route every {@code save()} of a
 * freshly-claimed record through {@code entityManager.merge()} instead of
 * {@code persist()} — an extra round-trip today, and a silent-lost-update
 * trap the moment any future code mutates the object {@link #claim}
 * returns instead of re-fetching it first.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "resource_type", nullable = false, updatable = false, length = 32)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdempotencyStatus status;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdempotencyRecord() {
        // JPA
    }

    /** A fresh claim always starts {@code IN_PROGRESS} with no stored outcome yet. */
    public static IdempotencyRecord claim(UUID userId, String idempotencyKey, String requestHash, String resourceType) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.id = UUID.randomUUID();
        record.userId = userId;
        record.idempotencyKey = idempotencyKey;
        record.requestHash = requestHash;
        record.resourceType = resourceType;
        record.status = IdempotencyStatus.IN_PROGRESS;
        return record;
    }

    public void markCompleted(UUID resourceId, int httpStatus, String responseBody) {
        this.status = IdempotencyStatus.COMPLETED;
        this.resourceId = resourceId;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    public void markFailed(int httpStatus, String responseBody) {
        this.status = IdempotencyStatus.FAILED;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
