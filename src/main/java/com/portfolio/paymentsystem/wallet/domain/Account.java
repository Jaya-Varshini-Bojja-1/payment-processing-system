package com.portfolio.paymentsystem.wallet.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.portfolio.paymentsystem.common.domain.Currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A user's INR wallet. {@code balancePaise} is a MATERIALIZED value — a
 * cache of "what the ledger says right now" kept only for fast reads. It is
 * never the authority on how much money an account holds; the ledger is
 * (see {@code com.portfolio.paymentsystem.ledger.LedgerService}). Every
 * change to this field happens in the same database transaction as the
 * ledger entries that justify it.
 * <p>
 * {@code user_id} is a plain UUID, not a JPA relationship to the identity
 * module's {@code User} entity — modules reference each other by id, not by
 * shared entity graph, keeping wallet decoupled from identity's persistence
 * model.
 * <p>
 * {@link #version} backs optimistic locking: two concurrent updates to the
 * same account race on this column, and the loser gets an
 * {@link jakarta.persistence.OptimisticLockException} instead of silently
 * overwriting the winner's change (the "lost update" problem).
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Column(name = "balance_paise", nullable = false)
    private long balancePaise;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // JPA
    }

    public static Account openInr(UUID userId) {
        Account account = new Account();
        account.id = UUID.randomUUID();
        account.userId = userId;
        account.currency = Currency.INR;
        account.balancePaise = 0L;
        return account;
    }

    /**
     * Applies a ledger entry's effect to this account's cached balance.
     * <p>
     * <b>Only {@code LedgerService} may call these</b> — it is also writing
     * the corresponding ledger row in the same database transaction. There
     * must be no code path that changes {@link #balancePaise} without a
     * ledger entry to justify it. Java has no cross-package "friend" access,
     * so this boundary is enforced by convention + code review, not the
     * compiler; it is intentionally kept as two tiny single-purpose methods
     * (rather than a public setter) to make misuse obvious in a diff.
     */
    public void applyCredit(long amountPaise) {
        this.balancePaise += amountPaise;
    }

    public void applyDebit(long amountPaise) {
        this.balancePaise -= amountPaise;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Currency getCurrency() {
        return currency;
    }

    public long getBalancePaise() {
        return balancePaise;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
