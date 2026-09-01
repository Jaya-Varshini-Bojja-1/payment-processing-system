package com.portfolio.paymentsystem.outbox.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.portfolio.paymentsystem.outbox.domain.InboxEvent;

public interface InboxEventRepository extends JpaRepository<InboxEvent, UUID> {
}
