package com.portfolio.paymentsystem.payment.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.portfolio.paymentsystem.common.domain.Currency;
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

import java.util.UUID;

/**
 * One atomic "durably accept this deposit/withdrawal request" attempt:
 * balance check (withdrawal only), the {@link PaymentTransaction} row, the
 * outbox dispatch event, and completing the idempotency claim — all in a
 * single database transaction, exactly like {@code TransferExecutionService},
 * but with no ledger post and no optimistic-lock retry here.
 * <p>
 * No ledger post: money has not moved yet — that only happens once the
 * (simulated) gateway confirms, in {@code PaymentOutcomeService}, in an
 * entirely separate transaction after a Kafka hop. No optimistic-lock
 * retry: this method never writes to {@code Account} (only reads its
 * balance for the withdrawal check), so there is no {@code @Version}
 * conflict it could ever lose.
 */
@Service
public class PaymentExecutionService {

    private final AccountRepository accountRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final IdempotencyService idempotencyService;
    private final PaymentEventPublisher paymentEventPublisher;

    public PaymentExecutionService(
            AccountRepository accountRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            IdempotencyService idempotencyService,
            PaymentEventPublisher paymentEventPublisher) {
        this.accountRepository = accountRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.idempotencyService = idempotencyService;
        this.paymentEventPublisher = paymentEventPublisher;
    }

    @Transactional
    public PaymentResponse attempt(UUID idempotencyRecordId, UUID userId, PaymentType type, long amountPaise) {
        Account account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> AccountNotFoundException.byUser(userId));

        // First-line check only -- not the authoritative one. The balance can
        // still move between now and gateway success; PaymentOutcomeService
        // re-checks immediately before the settlement ledger post, which is
        // the check that actually prevents an overdraft.
        if (type == PaymentType.WITHDRAWAL && account.getBalancePaise() < amountPaise) {
            throw new InsufficientBalanceException(account.getId(), account.getBalancePaise(), amountPaise);
        }

        PaymentTransaction payment = PaymentTransaction.initiate(userId, account.getId(), type, amountPaise, Currency.INR);
        paymentTransactionRepository.save(payment);

        // Same transaction as the row above: either both commit, or (any
        // later failure in this method) neither does. A PENDING payment can
        // never exist without a dispatch already durably queued for it.
        paymentEventPublisher.publishProcessingRequested(payment);

        PaymentResponse response = PaymentResponse.from(payment);
        idempotencyService.complete(idempotencyRecordId, payment.getId(), HttpStatus.ACCEPTED.value(), response);
        return response;
    }
}
