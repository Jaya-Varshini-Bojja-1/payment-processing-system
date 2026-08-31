package com.portfolio.paymentsystem.ledger.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The header row for one balanced money-movement event ("journal entry").
 * Immutable: there are no setters, and the database itself rejects
 * {@code UPDATE}/{@code DELETE} on this table (see V3 migration).
 * <p>
 * {@code referenceType} + {@code referenceId} optionally tag which business
 * operation produced this post (e.g. a future {@code Transfer} id). When
 * present, the database enforces a unique constraint on the pair so the
 * same business event can never post its ledger entries twice — a
 * defense-in-depth guard, independent of client-facing idempotency keys.
 */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reference_type", updatable = false, length = 64)
    private String referenceType;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @Column(updatable = false, length = 255)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
        // JPA
    }

    public static LedgerTransaction create(String referenceType, UUID referenceId, String description) {
        LedgerTransaction transaction = new LedgerTransaction();
        transaction.id = UUID.randomUUID();
        transaction.referenceType = referenceType;
        transaction.referenceId = referenceId;
        transaction.description = description;
        return transaction;
    }

    public UUID getId() {
        return id;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
