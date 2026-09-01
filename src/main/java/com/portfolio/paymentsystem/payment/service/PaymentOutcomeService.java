package com.portfolio.paymentsystem.payment.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.portfolio.paymentsystem.common.domain.Money;
import com.portfolio.paymentsystem.gateway.GatewayResult;
import com.portfolio.paymentsystem.ledger.service.LedgerLineItem;
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
import com.portfolio.paymentsystem.wallet.exception.AccountNotFoundException;
import com.portfolio.paymentsystem.wallet.repository.AccountRepository;

/**
 * Applies one {@code SimulatedPaymentGateway} result to a
 * {@link PaymentTransaction} — the only place M5 posts a payment's ledger
 * entries. Called by {@code PaymentProcessingConsumer} strictly
 * <b>after</b> the gateway call returns, never around it: the gateway is
 * external I/O and must never happen inside a Postgres transaction.
 *
 * <h2>Why the inbox insert is the LAST statement, not the first</h2>
 * {@code TransferCompletedListener} records its inbox entry <i>before</i>
 * doing any work, which is safe there because the only work after that
 * point is logging. Here, real work follows (a ledger post, a status
 * change) that must not silently vanish if this transaction is ever
 * retried. Recording the inbox row last means: if two deliveries of the
 * exact same {@code eventId} ever raced (or the same message is genuinely
 * redelivered after this transaction already committed), the LOSING
 * attempt's insert hits {@code inbox_events}' primary key, and — since
 * Postgres aborts the entire transaction the instant one statement errors
 * — that failure rolls back everything this method did in this attempt
 * (the ledger post included), leaving only the WINNING attempt's effects
 * durable. This deliberately does not reuse
 * {@code InboxEventGuard#recordIfNew} (which swallows that failure inside
 * its own dedicated transaction) because swallowing it here would let this
 * method return normally while the underlying connection is left aborted —
 * the same trap {@code IdempotencyService}'s javadoc documents for its own
 * claim/fallback split.
 */
@Service
public class PaymentOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeService.class);

    private final AccountRepository accountRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final InboxEventRepository inboxEventRepository;
    private final LedgerService ledgerService;
    private final PaymentEventPublisher paymentEventPublisher;
    private final PaymentProperties paymentProperties;

    public PaymentOutcomeService(
            AccountRepository accountRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            InboxEventRepository inboxEventRepository,
            LedgerService ledgerService,
            PaymentEventPublisher paymentEventPublisher,
            PaymentProperties paymentProperties) {
        this.accountRepository = accountRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.inboxEventRepository = inboxEventRepository;
        this.ledgerService = ledgerService;
        this.paymentEventPublisher = paymentEventPublisher;
        this.paymentProperties = paymentProperties;
    }

    /** Cheap pre-check so an already-resolved payment doesn't cost a gateway call on a late/duplicate delivery. */
    @Transactional(readOnly = true)
    public boolean isTerminal(UUID paymentTransactionId) {
        return paymentTransactionRepository.findById(paymentTransactionId)
                .map(payment -> payment.getStatus().isTerminal())
                .orElse(true);
    }

    /** Best-effort visibility for GET /payments/{id} while the gateway call is in flight; never blocks the caller. */
    @Transactional
    public void markProcessingIfPossible(UUID paymentTransactionId) {
        paymentTransactionRepository.findById(paymentTransactionId).ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.RETRYING) {
                payment.markProcessing();
            }
        });
    }

    /**
     * @throws DuplicatePaymentEventException if {@code eventId} was already applied by an earlier delivery.
     */
    @Transactional
    public void applyOutcome(UUID eventId, String eventType, UUID paymentTransactionId, GatewayResult result) {
        PaymentTransaction payment = paymentTransactionRepository.findById(paymentTransactionId)
                .orElseThrow(() -> new IllegalStateException("PaymentTransaction '" + paymentTransactionId + "' vanished mid-processing"));

        if (payment.getStatus().isTerminal()) {
            log.info("PaymentTransaction id={} already terminal (status={}); recording eventId={} as a no-op",
                    paymentTransactionId, payment.getStatus(), eventId);
        } else {
            applyToInFlightPayment(payment, result);
        }

        recordInboxOrThrow(eventId, eventType, paymentTransactionId);
    }

    private void applyToInFlightPayment(PaymentTransaction payment, GatewayResult result) {
        switch (result.outcome()) {
            case SUCCESS -> settle(payment, result.gatewayReference());
            case RETRYABLE_FAILURE -> retryOrExhaust(payment, result.failureReason());
            case NON_RETRYABLE_FAILURE -> payment.markFailed(result.failureReason());
        }
    }

    private void settle(PaymentTransaction payment, String gatewayReference) {
        Account clearing = accountRepository.findByAccountTypeAndCurrency(AccountType.CLEARING, payment.getCurrency())
                .orElseThrow(() -> new IllegalStateException("No clearing account provisioned for " + payment.getCurrency()));
        Money amount = Money.inr(payment.getAmountPaise());

        List<LedgerLineItem> lines;
        if (payment.getType() == PaymentType.WITHDRAWAL) {
            // The authoritative check: PaymentExecutionService's creation-time
            // check only rules out an obviously-doomed request. The balance
            // can have moved (a concurrent withdrawal, a P2P transfer out) in
            // the time between request acceptance and gateway confirmation,
            // so it must be re-verified against a fresh read, right here,
            // immediately before the debit that would otherwise overdraw the
            // account. See PROJECT_PLAN.md section 8C / section 9.
            Account account = accountRepository.findById(payment.getAccountId())
                    .orElseThrow(() -> AccountNotFoundException.byId(payment.getAccountId()));
            if (account.getBalancePaise() < payment.getAmountPaise()) {
                payment.markFailed("Insufficient balance at settlement");
                return;
            }
            lines = List.of(LedgerLineItem.debit(payment.getAccountId(), amount), LedgerLineItem.credit(clearing.getId(), amount));
        } else {
            lines = List.of(LedgerLineItem.credit(payment.getAccountId(), amount), LedgerLineItem.debit(clearing.getId(), amount));
        }

        ledgerService.postTransaction(LedgerPostingRequest.withReference(
                lines, "PAYMENT_TRANSACTION", payment.getId(), payment.getType() + " " + payment.getId()));
        payment.markSucceeded(gatewayReference);
    }

    private void retryOrExhaust(PaymentTransaction payment, String reason) {
        int attemptsIncludingThisOne = payment.getAttemptCount() + 1;
        if (attemptsIncludingThisOne >= paymentProperties.getMaxGatewayAttempts()) {
            payment.markRequiresManualReview(reason);
            paymentEventPublisher.publishExhausted(payment);
        } else {
            payment.scheduleRetry(reason);
            paymentEventPublisher.publishRetry(payment);
        }
    }

    private void recordInboxOrThrow(UUID eventId, String eventType, UUID aggregateId) {
        try {
            inboxEventRepository.saveAndFlush(InboxEvent.record(eventId, eventType, aggregateId));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicatePaymentEventException(eventId);
        }
    }
}
