package com.portfolio.paymentsystem.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.portfolio.paymentsystem.common.domain.Money;
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
 * Unit tests for the ledger's core financial invariants: every posting must
 * balance, must have at least two positive-amount lines, and must apply the
 * correct sign to each account's balance. Repositories are mocked so these
 * tests run without a database and focus purely on
 * {@link LedgerService}'s own validation and arithmetic.
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private LedgerTransactionRepository ledgerTransactionRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(accountRepository, ledgerTransactionRepository, ledgerEntryRepository);
    }

    private void stubHappyPathPersistence() {
        when(ledgerTransactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void postsBalancedTwoAccountMovementAndUpdatesBothBalances() {
        Account source = Account.openInr(UUID.randomUUID());
        Account destination = Account.openInr(UUID.randomUUID());
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(destination.getId())).thenReturn(Optional.of(destination));
        stubHappyPathPersistence();

        LedgerPostingRequest request = LedgerPostingRequest.of(List.of(
                LedgerLineItem.debit(source.getId(), Money.inr(50_000)),
                LedgerLineItem.credit(destination.getId(), Money.inr(50_000))
        ), "unit test movement");

        LedgerTransaction result = ledgerService.postTransaction(request);

        assertThat(result).isNotNull();
        // Debit decreases, credit increases — the two accounts' balances
        // move by exactly opposite amounts, so their sum is unchanged
        // (money moved, none was created or destroyed).
        assertThat(source.getBalancePaise()).isEqualTo(-50_000);
        assertThat(destination.getBalancePaise()).isEqualTo(50_000);
        assertThat(source.getBalancePaise() + destination.getBalancePaise()).isZero();
        verify(ledgerEntryRepository, times(2)).save(any());
    }

    @Test
    void postsBalancedThreeLineSplitMovement() {
        // One debit funding two credits demonstrates balancing is a total,
        // not just a 1:1 pairing.
        Account source = Account.openInr(UUID.randomUUID());
        Account destinationA = Account.openInr(UUID.randomUUID());
        Account destinationB = Account.openInr(UUID.randomUUID());
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(destinationA.getId())).thenReturn(Optional.of(destinationA));
        when(accountRepository.findById(destinationB.getId())).thenReturn(Optional.of(destinationB));
        stubHappyPathPersistence();

        LedgerPostingRequest request = LedgerPostingRequest.of(List.of(
                LedgerLineItem.debit(source.getId(), Money.inr(100_000)),
                LedgerLineItem.credit(destinationA.getId(), Money.inr(60_000)),
                LedgerLineItem.credit(destinationB.getId(), Money.inr(40_000))
        ), "unit test split");

        ledgerService.postTransaction(request);

        assertThat(source.getBalancePaise()).isEqualTo(-100_000);
        assertThat(destinationA.getBalancePaise()).isEqualTo(60_000);
        assertThat(destinationB.getBalancePaise()).isEqualTo(40_000);
    }

    @Test
    void rejectsFewerThanTwoLines() {
        LedgerPostingRequest request = LedgerPostingRequest.of(
                List.of(LedgerLineItem.credit(UUID.randomUUID(), Money.inr(100))), "single line");

        assertThatThrownBy(() -> ledgerService.postTransaction(request))
                .isInstanceOf(InvalidLedgerPostingException.class);
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(ledgerTransactionRepository);
    }

    @Test
    void rejectsZeroAmountLine() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        LedgerPostingRequest request = LedgerPostingRequest.of(List.of(
                LedgerLineItem.debit(a, Money.inr(0)),
                LedgerLineItem.credit(b, Money.inr(0))
        ), "zero amount");

        assertThatThrownBy(() -> ledgerService.postTransaction(request))
                .isInstanceOf(InvalidLedgerPostingException.class);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void rejectsUnbalancedPosting() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        LedgerPostingRequest request = LedgerPostingRequest.of(List.of(
                LedgerLineItem.debit(a, Money.inr(100)),
                LedgerLineItem.credit(b, Money.inr(99))
        ), "unbalanced");

        assertThatThrownBy(() -> ledgerService.postTransaction(request))
                .isInstanceOf(UnbalancedLedgerPostingException.class);
        // Validated before any account is even loaded — an invalid posting
        // never gets the chance to touch the database.
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(ledgerTransactionRepository);
        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void throwsWhenReferencedAccountDoesNotExist() {
        UUID missing = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(accountRepository.findById(missing)).thenReturn(Optional.empty());

        LedgerPostingRequest request = LedgerPostingRequest.of(List.of(
                LedgerLineItem.debit(missing, Money.inr(100)),
                LedgerLineItem.credit(other, Money.inr(100))
        ), "missing account");

        assertThatThrownBy(() -> ledgerService.postTransaction(request))
                .isInstanceOf(AccountNotFoundException.class);
        verifyNoInteractions(ledgerTransactionRepository);
    }

    @Test
    void translatesDuplicateReferenceViolationIntoDomainException() {
        Account a = Account.openInr(UUID.randomUUID());
        Account b = Account.openInr(UUID.randomUUID());
        when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        when(accountRepository.findById(b.getId())).thenReturn(Optional.of(b));
        when(ledgerTransactionRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        LedgerPostingRequest request = LedgerPostingRequest.withReference(
                List.of(
                        LedgerLineItem.debit(a.getId(), Money.inr(100)),
                        LedgerLineItem.credit(b.getId(), Money.inr(100))
                ),
                "TEST_EVENT", UUID.randomUUID(), "duplicate reference");

        assertThatThrownBy(() -> ledgerService.postTransaction(request))
                .isInstanceOf(DuplicateLedgerPostingException.class);
        // The header insert failed, so no lines and no balance changes were
        // ever applied for this attempt.
        verify(ledgerEntryRepository, never()).save(any());
        assertThat(a.getBalancePaise()).isZero();
        assertThat(b.getBalancePaise()).isZero();
    }
}
