package com.portfolio.paymentsystem.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.portfolio.paymentsystem.common.idempotency.service.IdempotencyService;
import com.portfolio.paymentsystem.ledger.domain.EntryType;
import com.portfolio.paymentsystem.ledger.domain.LedgerTransaction;
import com.portfolio.paymentsystem.ledger.service.LedgerPostingRequest;
import com.portfolio.paymentsystem.ledger.service.LedgerService;
import com.portfolio.paymentsystem.transfer.domain.Transfer;
import com.portfolio.paymentsystem.transfer.dto.TransferResponse;
import com.portfolio.paymentsystem.transfer.event.TransferEventPublisher;
import com.portfolio.paymentsystem.transfer.exception.InsufficientBalanceException;
import com.portfolio.paymentsystem.transfer.exception.RecipientAccountNotFoundException;
import com.portfolio.paymentsystem.transfer.repository.TransferRepository;
import com.portfolio.paymentsystem.wallet.domain.Account;
import com.portfolio.paymentsystem.wallet.exception.AccountNotFoundException;
import com.portfolio.paymentsystem.wallet.repository.AccountRepository;

/**
 * Unit tests for one atomic transfer attempt, with all collaborators
 * mocked — focused purely on "does this build the right ledger request and
 * refuse to move money it shouldn't," not on real transactions/locking
 * (that is {@code TransferConcurrencyIntegrationTest}'s job).
 */
@ExtendWith(MockitoExtension.class)
class TransferExecutionServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransferRepository transferRepository;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private TransferEventPublisher transferEventPublisher;

    private TransferExecutionService executionService;

    @BeforeEach
    void setUp() {
        executionService = new TransferExecutionService(
                accountRepository, transferRepository, ledgerService, idempotencyService, transferEventPublisher);
    }

    @Test
    void debitsSenderCreditsRecipientAndCompletesTheIdempotencyClaim() {
        Account sender = Account.openInr(UUID.randomUUID());
        Account recipient = Account.openInr(UUID.randomUUID());
        setBalance(sender, 100_000);
        UUID senderUserId = sender.getUserId();
        UUID recipientUserId = recipient.getUserId();
        UUID idempotencyRecordId = UUID.randomUUID();

        when(accountRepository.findByUserId(senderUserId)).thenReturn(Optional.of(sender));
        when(accountRepository.findByUserId(recipientUserId)).thenReturn(Optional.of(recipient));
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerService.postTransaction(any())).thenAnswer(inv -> LedgerTransaction.create("TRANSFER", UUID.randomUUID(), "test"));

        TransferResponse response = executionService.attempt(idempotencyRecordId, senderUserId, recipientUserId, 40_000);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.amount().amountPaise()).isEqualTo(40_000);
        // Applying the debit/credit to Account.balancePaise is LedgerService's
        // job alone (already covered by LedgerServiceTest); with LedgerService
        // mocked out here, we only assert this class asked it to do the right
        // thing (below), not that the balance actually moved.

        ArgumentCaptor<LedgerPostingRequest> requestCaptor = ArgumentCaptor.forClass(LedgerPostingRequest.class);
        verify(ledgerService).postTransaction(requestCaptor.capture());
        LedgerPostingRequest posted = requestCaptor.getValue();
        assertThat(posted.referenceType()).isEqualTo("TRANSFER");
        assertThat(posted.lines()).hasSize(2);
        assertThat(posted.lines().get(0).entryType()).isEqualTo(EntryType.DEBIT);
        assertThat(posted.lines().get(0).accountId()).isEqualTo(sender.getId());
        assertThat(posted.lines().get(1).entryType()).isEqualTo(EntryType.CREDIT);
        assertThat(posted.lines().get(1).accountId()).isEqualTo(recipient.getId());

        verify(idempotencyService).complete(eq(idempotencyRecordId), any(), eq(201), any(TransferResponse.class));

        // The outbox write happens on every successful attempt, in the same
        // (mocked-out-here, real-in-production) transaction as the rest of
        // this method — see TransferOutboxIntegrationTest for the proof that
        // it is genuinely atomic with the ledger post against real Postgres.
        ArgumentCaptor<Transfer> publishedTransferCaptor = ArgumentCaptor.forClass(Transfer.class);
        verify(transferEventPublisher).publishCompleted(publishedTransferCaptor.capture());
        assertThat(publishedTransferCaptor.getValue().getId()).isEqualTo(response.id());
    }

    @Test
    void throwsWhenSenderHasNoAccountAndTouchesNothingElse() {
        UUID senderUserId = UUID.randomUUID();
        UUID recipientUserId = UUID.randomUUID();
        when(accountRepository.findByUserId(senderUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executionService.attempt(UUID.randomUUID(), senderUserId, recipientUserId, 1_000))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(ledgerService, transferRepository, idempotencyService, transferEventPublisher);
    }

    @Test
    void throwsWhenRecipientHasNoAccount() {
        Account sender = Account.openInr(UUID.randomUUID());
        UUID recipientUserId = UUID.randomUUID();
        when(accountRepository.findByUserId(sender.getUserId())).thenReturn(Optional.of(sender));
        when(accountRepository.findByUserId(recipientUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executionService.attempt(UUID.randomUUID(), sender.getUserId(), recipientUserId, 1_000))
                .isInstanceOf(RecipientAccountNotFoundException.class);

        verifyNoInteractions(ledgerService, transferRepository, idempotencyService, transferEventPublisher);
    }

    @Test
    void rejectsInsufficientBalanceWithoutTouchingTheLedgerOrTransferTable() {
        Account sender = Account.openInr(UUID.randomUUID());
        Account recipient = Account.openInr(UUID.randomUUID());
        setBalance(sender, 1_000);
        when(accountRepository.findByUserId(sender.getUserId())).thenReturn(Optional.of(sender));
        when(accountRepository.findByUserId(recipient.getUserId())).thenReturn(Optional.of(recipient));

        assertThatThrownBy(() -> executionService.attempt(UUID.randomUUID(), sender.getUserId(), recipient.getUserId(), 5_000))
                .isInstanceOf(InsufficientBalanceException.class);

        verifyNoInteractions(ledgerService, transferRepository, idempotencyService, transferEventPublisher);
    }

    private void setBalance(Account account, long balancePaise) {
        account.applyCredit(balancePaise);
    }
}
