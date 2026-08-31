package com.portfolio.paymentsystem.ledger.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.portfolio.paymentsystem.common.domain.Currency;
import com.portfolio.paymentsystem.common.domain.Money;
import com.portfolio.paymentsystem.ledger.domain.EntryType;
import com.portfolio.paymentsystem.ledger.domain.LedgerEntry;
import com.portfolio.paymentsystem.ledger.domain.LedgerTransaction;
import com.portfolio.paymentsystem.ledger.exception.DuplicateLedgerPostingException;
import com.portfolio.paymentsystem.ledger.exception.InvalidLedgerPostingException;
import com.portfolio.paymentsystem.ledger.exception.UnbalancedLedgerPostingException;
import com.portfolio.paymentsystem.ledger.repository.LedgerEntryRepository;
import com.portfolio.paymentsystem.ledger.repository.LedgerTransactionRepository;
import com.portfolio.paymentsystem.wallet.domain.Account;
import com.portfolio.paymentsystem.wallet.exception.AccountNotFoundException;
import com.portfolio.paymentsystem.wallet.repository.AccountRepository;

/**
 * The single write path for money movement in this system. Every future
 * feature that changes a balance (Transfer in M3, PaymentTransaction in M5)
 * must go through {@link #postTransaction(LedgerPostingRequest)} rather
 * than touching {@code Account.balancePaise} directly — that is what
 * guarantees the ledger and the materialized balance never drift apart.
 *
 * <h2>Concurrency contract</h2>
 * This method does <b>not</b> catch or retry
 * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}.
 * A version conflict means another transaction changed one of these
 * accounts between when we read it and when we tried to commit; retrying
 * blindly here (with no knowledge of *why* the caller is posting) could
 * paper over a business-level decision that needs to be re-evaluated with
 * fresh data (e.g. a transfer should re-check "is there still enough
 * balance?", not just re-apply the same delta). Deciding whether/how to
 * retry belongs to the calling service (Transfer, PaymentTransaction),
 * which understands the operation's idempotency story. This method's job
 * is narrower: make one atomic, correctly-balanced attempt, and fail
 * loudly if it cannot.
 *
 * <h2>Why no lock ordering / deadlock avoidance</h2>
 * Because locking here is purely optimistic (a {@code version} column
 * checked at {@code UPDATE} time, never {@code SELECT ... FOR UPDATE}),
 * two concurrent postings touching the same two accounts never block each
 * other waiting for a row lock — there is no lock to wait for. The loser
 * simply fails its version check at commit. This is precisely why V1 uses
 * optimistic locking: it sidesteps deadlock-ordering complexity entirely.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(
            AccountRepository accountRepository,
            LedgerTransactionRepository ledgerTransactionRepository,
            LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public LedgerTransaction postTransaction(LedgerPostingRequest request) {
        validateStructure(request);
        validateBalanced(request);

        Map<UUID, Account> accountsById = loadAccounts(request);

        LedgerTransaction transaction = LedgerTransaction.create(
                request.referenceType(), request.referenceId(), request.description());
        saveTransactionHeader(transaction);

        for (LedgerLineItem line : request.lines()) {
            Account account = accountsById.get(line.accountId());
            LedgerEntry entry = LedgerEntry.of(
                    transaction.getId(), account.getId(), line.entryType(),
                    line.amount().amountPaise(), line.amount().currency());
            ledgerEntryRepository.save(entry);

            if (line.entryType() == EntryType.CREDIT) {
                account.applyCredit(line.amount().amountPaise());
            } else {
                account.applyDebit(line.amount().amountPaise());
            }
        }

        log.info("Posted ledger transaction id={} referenceType={} referenceId={} lines={}",
                transaction.getId(), request.referenceType(), request.referenceId(), request.lines().size());
        return transaction;
    }

    private void validateStructure(LedgerPostingRequest request) {
        if (request.lines().size() < 2) {
            throw new InvalidLedgerPostingException("A ledger posting requires at least two lines, got " + request.lines().size());
        }
        for (LedgerLineItem line : request.lines()) {
            if (line.amount().amountPaise() <= 0) {
                throw new InvalidLedgerPostingException("Ledger line amounts must be positive, got " + line.amount().amountPaise());
            }
            if (line.amount().currency() != Currency.INR) {
                throw new InvalidLedgerPostingException("Only INR is supported in V1, got " + line.amount().currency());
            }
        }
    }

    private void validateBalanced(LedgerPostingRequest request) {
        long totalDebit = 0;
        long totalCredit = 0;
        for (LedgerLineItem line : request.lines()) {
            if (line.entryType() == EntryType.DEBIT) {
                totalDebit += line.amount().amountPaise();
            } else {
                totalCredit += line.amount().amountPaise();
            }
        }
        if (totalDebit != totalCredit) {
            throw new UnbalancedLedgerPostingException(totalDebit, totalCredit);
        }
    }

    private Map<UUID, Account> loadAccounts(LedgerPostingRequest request) {
        Map<UUID, Account> accountsById = new LinkedHashMap<>();
        for (LedgerLineItem line : request.lines()) {
            accountsById.computeIfAbsent(line.accountId(), id ->
                    accountRepository.findById(id).orElseThrow(() -> AccountNotFoundException.byId(id)));
        }
        return accountsById;
    }

    /**
     * Flushed immediately (rather than left for end-of-transaction flush) so
     * a duplicate {@code (referenceType, referenceId)} is caught here, with
     * a clear domain exception, instead of surfacing as an opaque
     * constraint-violation error at commit time.
     */
    private void saveTransactionHeader(LedgerTransaction transaction) {
        try {
            ledgerTransactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateLedgerPostingException(transaction.getReferenceType(), transaction.getReferenceId());
        }
    }
}
