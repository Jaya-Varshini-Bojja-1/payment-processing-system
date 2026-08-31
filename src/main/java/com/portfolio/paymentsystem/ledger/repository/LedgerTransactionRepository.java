package com.portfolio.paymentsystem.ledger.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.portfolio.paymentsystem.ledger.domain.LedgerTransaction;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {
}
