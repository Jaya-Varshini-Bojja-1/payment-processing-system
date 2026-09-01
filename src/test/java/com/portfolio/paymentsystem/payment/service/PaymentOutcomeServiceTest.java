package com.portfolio.paymentsystem.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import org.springframework.dao.DataIntegrityViolationException;

import com.portfolio.paymentsystem.common.domain.Currency;
import com.portfolio.paymentsystem.gateway.GatewayResult;
import com.portfolio.paymentsystem.ledger.domain.LedgerTransaction;
import com.portfolio.paymentsystem.ledger.service.LedgerPostingRequest;
import com.portfolio.paymentsystem.ledger.service.LedgerService;
import com.portfolio.paymentsystem.outbox.domain.InboxEvent;
import com.portfolio.paymentsystem.outbox.repository.InboxEventRepository;
import com.portfolio.paymentsystem.payment.domain.PaymentStatus;
import com.portfolio.paymentsystem.payment.domain.PaymentTransaction;
import com.portfolio.paymentsystem.payment.domain.PaymentType;
import com.portfolio.paymentsystem.payment.event.PaymentEventPublisher;
import com.portfolio.paymentsystem.payment.exception.DuplicatePaymentEventException;
import com.portfolio.paymentsystem.payment.repository.PaymentTransactionRepository;
import com.portfolio.paymentsystem.wallet.domain.Account;
import com.portfolio.paymentsystem.wallet.domain.AccountType;
import com.portfolio.paymentsystem.wallet.repository.AccountRepository;

/**
 * Unit tests for the only place M5 posts a payment's ledger entries, with
 * all collaborators mocked. Focuses on: which ledger lines get built for a
 * deposit vs a withdrawal, the settlement-time balance re-check, the
 * retry/exhaust decision, and that a terminal payment or a duplicate
 * delivery never re-applies an outcome. Real transaction-boundary behavior
 * (the "insert-last" rollback story documented on {@code PaymentOutcomeService})
 * is covered separately by an integration test against real PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class PaymentOutcomeServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private InboxEventRepository inboxEventRepository;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private PaymentEventPublisher paymentEventPublisher;

    private PaymentOutcomeService outcomeService;
    private PaymentProperties paymentProperties;

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final String EVENT_TYPE = "PaymentProcessingRequested:0";

    @BeforeEach
    void setUp() {
        paymentProperties = new PaymentProperties();
        paymentProperties.setMaxGatewayAttempts(3);
        outcomeService = new PaymentOutcomeService(
                accountRepository, paymentTransactionRepository, inboxEventRepository, ledgerService,
                paymentEventPublisher, paymentProperties);
    }

    private Account accountWithBalance(long balancePaise) {
        Account account = Account.openInr(UUID.randomUUID());
        account.applyCredit(balancePaise);
        return account;
    }

    private void stubLedgerSuccess() {
        when(ledgerService.postTransaction(any())).thenAnswer(
                inv -> LedgerTransaction.create("PAYMENT_TRANSACTION", UUID.randomUUID(), "test"));
    }

    @Test
    void depositSuccessCreditsUserAndDebitsClearingThenMarksSucceeded() {
        Account userAccount = accountWithBalance(0);
        Account clearing = Account.openInr(UUID.randomUUID());
        PaymentTransaction payment = PaymentTransaction.initiate(userAccount.getUserId(), userAccount.getId(), PaymentType.DEPOSIT, 50_000, Currency.INR);
        when(paymentTransactionRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(accountRepository.findByAccountTypeAndCurrency(AccountType.CLEARING, Currency.INR)).thenReturn(Optional.of(clearing));
        stubLedgerSuccess();

        outcomeService.applyOutcome(EVENT_ID, EVENT_TYPE, payment.getId(), GatewayResult.success("SIM-ref"));

        ArgumentCaptor<LedgerPostingRequest> captor = ArgumentCaptor.forClass(LedgerPostingRequest.class);
        verify(ledgerService).postTransaction(captor.capture());
        LedgerPostingRequest posted = captor.getValue();
        assertThat(posted.referenceType()).isEqualTo("PAYMENT_TRANSACTION");
        assertThat(posted.referenceId()).isEqualTo(payment.getId());
        assertThat(posted.lines()).hasSize(2);
        assertThat(posted.lines().get(0).accountId()).isEqualTo(userAccount.getId());
        assertThat(posted.lines().get(0).entryType().name()).isEqualTo("CREDIT");
        assertThat(posted.lines().get(1).accountId()).isEqualTo(clearing.getId());
        assertThat(posted.lines().get(1).entryType().name()).isEqualTo("DEBIT");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getGatewayReference()).isEqualTo("SIM-ref");
        verify(inboxEventRepository).saveAndFlush(any(InboxEvent.class));
    }

    @Test
    void withdrawalSuccessDebitsUserAndCreditsClearing() {
        Account userAccount = accountWithBalance(100_000);
        Account clearing = Account.openInr(UUID.randomUUID());
        PaymentTransaction payment = PaymentTransaction.initiate(userAccount.getUserId(), userAccount.getId(), PaymentType.WITHDRAWAL, 40_000, Currency.INR);
        when(paymentTransactionRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(accountRepository.findByAccountTypeAndCurrency(AccountType.CLEARING, Currency.INR)).thenReturn(Optional.of(clearing));
        when(accountRepository.findById(userAccount.getId())).thenReturn(Optional.of(userAccount));
        stubLedgerSuccess();

        outcomeService.applyOutcome(EVENT_ID, EVENT_TYPE, payment.getId(), GatewayResult.success("SIM-ref"));

        ArgumentCaptor<LedgerPostingRequest> captor = ArgumentCaptor.forClass(LedgerPostingRequest.class);
        verify(ledgerService).postTransaction(captor.capture());
        assertThat(captor.getValue().lines().get(0).entryType().name()).isEqualTo("DEBIT");
        assertThat(captor.getValue().lines().get(0).accountId()).isEqualTo(userAccount.getId());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void withdrawalReCheckedInsufficientBalanceAtSettlementFailsWithoutPostingTheLedger() {
        // Balance moved down (e.g. a concurrent spend) between creation-time
        // check and gateway confirmation -- the re-check here must catch it.
        Account userAccount = accountWithBalance(10_000);
        Account clearing = Account.openInr(UUID.randomUUID());
        PaymentTransaction payment = PaymentTransaction.initiate(userAccount.getUserId(), userAccount.getId(), PaymentType.WITHDRAWAL, 40_000, Currency.INR);
        when(paymentTransactionRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(accountRepository.findByAccountTypeAndCurrency(AccountType.CLEARING, Currency.INR)).thenReturn(Optional.of(clearing));
        when(accountRepository.findById(userAccount.getId())).thenReturn(Optional.of(userAccount));

        outcomeService.applyOutcome(EVENT_ID, EVENT_TYPE, payment.getId(), GatewayResult.success("SIM-ref"));

        verifyNoInteractions(ledgerService);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).containsIgnoringCase("insufficient balance");
    }

    @Test
    void retryableFailureBelowMaxAttemptsSchedulesARetryInsteadOfGivingUp() {
        PaymentTransaction payment = PaymentTransaction.initiate(UUID.randomUUID(), UUID.randomUUID(), PaymentType.DEPOSIT, 50_000, Currency.INR);
        when(paymentTransactionRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        outcomeService.applyOutcome(EVENT_ID, EVENT_TYPE, payment.getId(), GatewayResult.retryableFailure("simulated timeout"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RETRYING);
        assertThat(payment.getAttemptCount()).isEqualTo(1);
        verify(paymentEventPublisher).publishRetry(payment);
        verify(paymentEventPublisher, never()).publishExhausted(any());
        verifyNoInteractions(ledgerService);
    }

    @Test
    void retryableFailureAtMaxAttemptsMovesToManualReviewAndPublishesExhausted() {
        paymentProperties.setMaxGatewayAttempts(1);
        PaymentTransaction payment = PaymentTransaction.initiate(UUID.randomUUID(), UUID.randomUUID(), PaymentType.WITHDRAWAL, 50_000, Currency.INR);
        when(paymentTransactionRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        outcomeService.applyOutcome(EVENT_ID, EVENT_TYPE, payment.getId(), GatewayResult.retryableFailure("simulated timeout"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUIRES_MANUAL_REVIEW);
        assertThat(payment.getAttemptCount()).isEqualTo(1);
        verify(paymentEventPublisher).publishExhausted(payment);
        verify(paymentEventPublisher, never()).publishRetry(any());
    }

    @Test
    void nonRetryableFailureIsTerminalWithoutAnyRetryDispatch() {
        PaymentTransaction payment = PaymentTransaction.initiate(UUID.randomUUID(), UUID.randomUUID(), PaymentType.DEPOSIT, 50_000, Currency.INR);
        when(paymentTransactionRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        outcomeService.applyOutcome(EVENT_ID, EVENT_TYPE, payment.getId(), GatewayResult.nonRetryableFailure("simulated decline"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verifyNoInteractions(ledgerService, paymentEventPublisher);
    }

    @Test
    void alreadyTerminalPaymentIsLeftUntouchedButTheDeliveryIsStillRecorded() {
        PaymentTransaction payment = PaymentTransaction.initiate(UUID.randomUUID(), UUID.randomUUID(), PaymentType.DEPOSIT, 50_000, Currency.INR);
        payment.markSucceeded("SIM-earlier");
        when(paymentTransactionRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        outcomeService.applyOutcome(EVENT_ID, EVENT_TYPE, payment.getId(), GatewayResult.retryableFailure("late duplicate delivery"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verifyNoInteractions(ledgerService, paymentEventPublisher);
        verify(inboxEventRepository).saveAndFlush(any(InboxEvent.class));
    }

    @Test
    void duplicateInboxInsertIsSurfacedAsADomainSpecificException() {
        PaymentTransaction payment = PaymentTransaction.initiate(UUID.randomUUID(), UUID.randomUUID(), PaymentType.DEPOSIT, 50_000, Currency.INR);
        when(paymentTransactionRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(inboxEventRepository.saveAndFlush(any(InboxEvent.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> outcomeService.applyOutcome(EVENT_ID, EVENT_TYPE, payment.getId(), GatewayResult.nonRetryableFailure("decline")))
                .isInstanceOf(DuplicatePaymentEventException.class);
    }

    @Test
    void isTerminalReflectsThePersistedStatus() {
        PaymentTransaction pending = PaymentTransaction.initiate(UUID.randomUUID(), UUID.randomUUID(), PaymentType.DEPOSIT, 1_000, Currency.INR);
        when(paymentTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        assertThat(outcomeService.isTerminal(pending.getId())).isFalse();

        pending.markFailed("simulated decline");
        assertThat(outcomeService.isTerminal(pending.getId())).isTrue();
    }

    @Test
    void markProcessingIfPossibleTransitionsPendingButNotAlreadyTerminalPayments() {
        PaymentTransaction pending = PaymentTransaction.initiate(UUID.randomUUID(), UUID.randomUUID(), PaymentType.DEPOSIT, 1_000, Currency.INR);
        when(paymentTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        outcomeService.markProcessingIfPossible(pending.getId());

        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }
}
