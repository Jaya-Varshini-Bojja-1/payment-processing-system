package com.portfolio.paymentsystem.common.idempotency.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.portfolio.paymentsystem.common.idempotency.domain.IdempotencyRecord;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
