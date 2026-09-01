package com.portfolio.paymentsystem.wallet.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.portfolio.paymentsystem.common.domain.Currency;
import com.portfolio.paymentsystem.wallet.domain.Account;
import com.portfolio.paymentsystem.wallet.domain.AccountType;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    /**
     * The single system counterparty M5 posts deposits/withdrawals against
     * (see the V6 migration's seed row). Looked up by type+currency rather
     * than a hardcoded id constant so the payment module never needs to
     * know the seed migration's literal UUID.
     */
    Optional<Account> findByAccountTypeAndCurrency(AccountType accountType, Currency currency);
}
