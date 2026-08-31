package com.portfolio.paymentsystem.ledger.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.portfolio.paymentsystem.common.domain.Currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One immutable line of a {@link LedgerTransaction}: "this account's
 * balance moved by this amount, this direction." {@code accountId} is a
 * plain UUID (not a JPA relationship) so the ledger module stays decoupled
 * from the wallet module's entity graph.
 * <p>
 * Never updated or deleted after insert (enforced by a database trigger,
 * see the V3 migration) — this is what makes the ledger trustworthy as a
 * historical record: nothing can quietly rewrite what happened.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "ledger_transaction_id", nullable = false, updatable = false)
    private UUID ledgerTransactionId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 6, updatable = false)
    private EntryType entryType;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // JPA
    }

    public static LedgerEntry of(UUID ledgerTransactionId, UUID accountId, EntryType entryType, long amountPaise, Currency currency) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amountPaise must be positive: " + amountPaise);
        }
        LedgerEntry entry = new LedgerEntry();
        entry.id = UUID.randomUUID();
        entry.ledgerTransactionId = ledgerTransactionId;
        entry.accountId = accountId;
        entry.entryType = entryType;
        entry.amountPaise = amountPaise;
        entry.currency = currency;
        return entry;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
