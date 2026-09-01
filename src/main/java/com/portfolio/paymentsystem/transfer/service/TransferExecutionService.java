package com.portfolio.paymentsystem.transfer.service;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.portfolio.paymentsystem.common.domain.Currency;
import com.portfolio.paymentsystem.common.domain.Money;
import com.portfolio.paymentsystem.common.idempotency.service.IdempotencyService;
import com.portfolio.paymentsystem.ledger.service.LedgerLineItem;
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
 * One complete, atomic attempt at a P2P transfer: fresh account reads,
 * sufficient-balance check, {@link Transfer} row, ledger post, and marking
 * the idempotency claim {@code COMPLETED} — all in a single database
 * transaction. Either all of it commits, or (any exception, including an
 * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
 * surfacing when this transaction commits) none of it does.
 *
 * <h2>Why this class does not retry</h2>
 * This is deliberately the same stance as {@code LedgerService}: a single
 * failed attempt here says nothing about whether trying again is safe or
 * even meaningful — that is a decision for whoever understands the whole
 * business operation. {@code TransferService} is that caller: it invokes
 * {@link #attempt} again with a brand new transaction (and therefore a
 * brand new read of both accounts) on an optimistic-lock conflict, so the
 * balance check below is always evaluated against current truth, never a
 * stale read from a previous, failed attempt.
 */
@Service
public class TransferExecutionService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;
    private final TransferEventPublisher transferEventPublisher;

    public TransferExecutionService(
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            LedgerService ledgerService,
            IdempotencyService idempotencyService,
            TransferEventPublisher transferEventPublisher) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
        this.transferEventPublisher = transferEventPublisher;
    }

    @Transactional
    public TransferResponse attempt(UUID idempotencyRecordId, UUID senderUserId, UUID recipientUserId, long amountPaise) {
        Account sender = accountRepository.findByUserId(senderUserId)
                .orElseThrow(() -> AccountNotFoundException.byUser(senderUserId));
        Account recipient = accountRepository.findByUserId(recipientUserId)
                .orElseThrow(() -> new RecipientAccountNotFoundException(recipientUserId));

        if (sender.getBalancePaise() < amountPaise) {
            throw new InsufficientBalanceException(sender.getId(), sender.getBalancePaise(), amountPaise);
        }

        Transfer transfer = Transfer.initiate(
                senderUserId, sender.getId(), recipient.getId(), recipientUserId, amountPaise, Currency.INR);
        transferRepository.save(transfer);

        ledgerService.postTransaction(LedgerPostingRequest.withReference(
                List.of(
                        LedgerLineItem.debit(sender.getId(), Money.inr(amountPaise)),
                        LedgerLineItem.credit(recipient.getId(), Money.inr(amountPaise))),
                "TRANSFER", transfer.getId(), "P2P transfer " + transfer.getId()));

        transfer.markCompleted();

        // Same transaction as everything above: the outbox row for
        // TransferCompleted either commits with the transfer + ledger, or
        // (on any later failure in this method, or the caller rolling back
        // on an optimistic-lock conflict) rolls back with them too. There is
        // no way for money to move without this row existing, or for this
        // row to exist without money having moved.
        transferEventPublisher.publishCompleted(transfer);

        TransferResponse response = TransferResponse.from(transfer);
        idempotencyService.complete(idempotencyRecordId, transfer.getId(), HttpStatus.CREATED.value(), response);
        return response;
    }
}
