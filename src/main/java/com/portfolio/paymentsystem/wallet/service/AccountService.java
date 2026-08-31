package com.portfolio.paymentsystem.wallet.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.portfolio.paymentsystem.ledger.domain.LedgerEntry;
import com.portfolio.paymentsystem.ledger.repository.LedgerEntryRepository;
import com.portfolio.paymentsystem.wallet.domain.Account;
import com.portfolio.paymentsystem.wallet.exception.AccountAccessDeniedException;
import com.portfolio.paymentsystem.wallet.exception.AccountAlreadyExistsException;
import com.portfolio.paymentsystem.wallet.exception.AccountNotFoundException;
import com.portfolio.paymentsystem.wallet.repository.AccountRepository;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public AccountService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * {@code existsByUserId} is only an optimization to fail fast without a
     * round trip to the constraint violation path; it is NOT what makes
     * "one account per user" safe under concurrency (two simultaneous
     * requests could both pass this check before either commits). The
     * database's {@code uq_accounts_user_id} unique constraint is the real
     * guarantee — caught here and translated into a clean 409.
     */
    @Transactional
    public Account openAccount(UUID userId) {
        if (accountRepository.existsByUserId(userId)) {
            throw new AccountAlreadyExistsException(userId);
        }
        try {
            Account account = Account.openInr(userId);
            Account saved = accountRepository.saveAndFlush(account);
            log.info("Opened account id={} userId={}", saved.getId(), userId);
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new AccountAlreadyExistsException(userId);
        }
    }

    @Transactional(readOnly = true)
    public Account getMyAccount(UUID userId) {
        return accountRepository.findByUserId(userId)
                .orElseThrow(() -> AccountNotFoundException.byUser(userId));
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntry> getLedgerHistory(UUID accountId, UUID callerUserId, Pageable pageable) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> AccountNotFoundException.byId(accountId));
        if (!account.getUserId().equals(callerUserId)) {
            throw new AccountAccessDeniedException(accountId);
        }
        return ledgerEntryRepository.findByAccountId(accountId, pageable);
    }
}
