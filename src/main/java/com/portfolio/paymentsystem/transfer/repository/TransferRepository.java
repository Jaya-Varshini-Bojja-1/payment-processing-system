package com.portfolio.paymentsystem.transfer.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.portfolio.paymentsystem.transfer.domain.Transfer;
import com.portfolio.paymentsystem.transfer.domain.TransferStatus;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    /**
     * "Transfers involving me" as either party, newest first, with an
     * optional status filter. {@code destinationUserId} is denormalized
     * onto {@link Transfer} (see the V4 migration) precisely so this query
     * needs no join against {@code accounts}.
     */
    @Query("""
            SELECT t FROM Transfer t
            WHERE (t.initiatorUserId = :userId OR t.destinationUserId = :userId)
            AND (:status IS NULL OR t.status = :status)
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    Page<Transfer> findForUser(@Param("userId") UUID userId, @Param("status") TransferStatus status, Pageable pageable);
}
