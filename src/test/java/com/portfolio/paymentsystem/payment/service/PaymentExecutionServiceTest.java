package com.portfolio.paymentsystem.payment.service;

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
import com.portfolio.paymentsystem.payment.domain.PaymentTransaction;
import com.portfolio.paymentsystem.payment.domain.PaymentType;
import com.portfolio.paymentsystem.payment.dto.PaymentResponse;
import com.portfolio.paymentsystem.payment.event.PaymentEventPublisher;
import com.portfolio.paymentsystem.payment.exception.InsufficientBalanceException;
import com.portfolio.paymentsystem.payment.repository.PaymentTransactionRepository;
import com.portfolio.paymentsystem.wallet.domain.Account;
import com.portfolio.paymentsystem.wallet.exception.AccountNotFoundException;
import com.portfolio.paymentsystem.wallet.repository.AccountRepository;

/**
 * Unit tests for one atomic "durably accept the request" attempt, with all
 * collaborators mocked -- mirrors {@code TransferExecutionServiceTest}'s
 * style. No optimistic-lock scenario exists here to test (unlike transfers):
 * this method never writes to {@code Account}.
 */
@ExtendWith(MockitoExtension.class)
class PaymentExecutionServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private PaymentEventPublisher paymentEventPublisher;

    private PaymentExecutionService executionService;

    @BeforeEach
    void setUp() {
        executionService = new PaymentExecutionService(
                accountRepository, paymentTransactionRepository, idempotencyService, paymentEventPublisher);
    }

    @Test
    void depositIsAcceptedWithoutAnyBalanceCheckAndDispatchedToTheOutbox() {
        Account account = Account.openInr(UUID.randomUUID());
        UUID idempotencyRecordId = UUID.randomUUID();
        when(accountRepository.findByUserId(account.getUserId())).thenReturn(Optional.of(account));
        when(paymentTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = executionService.attempt(idempotencyRecordId, account.getUserId(), PaymentType.DEPOSIT, 50_000);

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.type()).isEqualTo("DEPOSIT");
        assertThat(response.amount().amountPaise()).isEqualTo(50_000);

        ArgumentCaptor<PaymentTransaction> publishedCaptor = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(paymentEventPublisher).publishProcessingRequested(publishedCaptor.capture());
        assertThat(publishedCaptor.getValue().getId()).isEqualTo(response.id());

        verify(idempotencyService).complete(eq(idempotencyRecordId), eq(response.id()), eq(202), any(PaymentResponse.class));
    }

    @Test
    void withdrawalWithSufficientBalanceIsAcceptedAndDispatched() {
        Account account = Account.openInr(UUID.randomUUID());
        account.applyCredit(100_000);
        when(accountRepository.findByUserId(account.getUserId())).thenReturn(Optional.of(account));
        when(paymentTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = executionService.attempt(UUID.randomUUID(), account.getUserId(), PaymentType.WITHDRAWAL, 40_000);

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.type()).isEqualTo("WITHDRAWAL");
        verify(paymentEventPublisher).publishProcessingRequested(any());
    }

    @Test
    void withdrawalWithInsufficientBalanceIsRejectedBeforeTouchingTheOutboxOrIdempotencyService() {
        Account account = Account.openInr(UUID.randomUUID());
        account.applyCredit(1_000);
        when(accountRepository.findByUserId(account.getUserId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> executionService.attempt(UUID.randomUUID(), account.getUserId(), PaymentType.WITHDRAWAL, 5_000))
                .isInstanceOf(InsufficientBalanceException.class);

        verifyNoInteractions(paymentTransactionRepository, idempotencyService, paymentEventPublisher);
    }

    @Test
    void throwsWhenTheCallerHasNoAccountAndTouchesNothingElse() {
        UUID userId = UUID.randomUUID();
        when(accountRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executionService.attempt(UUID.randomUUID(), userId, PaymentType.DEPOSIT, 1_000))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(paymentTransactionRepository, idempotencyService, paymentEventPublisher);
    }
}
