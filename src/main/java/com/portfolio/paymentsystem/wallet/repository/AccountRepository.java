package com.portfolio.paymentsystem.wallet.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.portfolio.paymentsystem.wallet.domain.Account;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);
}
