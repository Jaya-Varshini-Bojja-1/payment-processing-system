package com.portfolio.paymentsystem.payment.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.portfolio.paymentsystem.payment.domain.PaymentStatus;
import com.portfolio.paymentsystem.payment.domain.PaymentTransaction;
import com.portfolio.paymentsystem.payment.domain.PaymentType;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    @Query("""
            SELECT p FROM PaymentTransaction p
            WHERE p.userId = :userId
            AND (:type IS NULL OR p.type = :type)
            AND (:status IS NULL OR p.status = :status)
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    Page<PaymentTransaction> findForUser(
            @Param("userId") UUID userId, @Param("type") PaymentType type,
            @Param("status") PaymentStatus status, Pageable pageable);
}
