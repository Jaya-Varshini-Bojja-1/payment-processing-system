package com.portfolio.paymentsystem.outbox.service;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.portfolio.paymentsystem.outbox.domain.InboxEvent;
import com.portfolio.paymentsystem.outbox.repository.InboxEventRepository;

/**
 * The single "have I already handled this event?" check every
 * {@code @KafkaListener} in this application must go through. Uses the same
 * "attempt the insert, treat a unique violation as the answer" pattern as
 * {@code IdempotencyService#claim} rather than "check then insert" — the
 * database's primary key on {@code event_id}, not application logic, is
 * what actually arbitrates two near-simultaneous redeliveries (e.g. during
 * a consumer-group rebalance).
 */
@Service
public class InboxEventGuard {

    private final InboxEventRepository inboxEventRepository;

    public InboxEventGuard(InboxEventRepository inboxEventRepository) {
        this.inboxEventRepository = inboxEventRepository;
    }

    /**
     * @return {@code true} if this call just recorded a genuinely new event
     *         (the caller should proceed with its reaction to it);
     *         {@code false} if it was already recorded (the caller must
     *         skip its reaction — this delivery is a duplicate).
     */
    @Transactional
    public boolean recordIfNew(UUID eventId, String eventType, UUID aggregateId) {
        try {
            inboxEventRepository.saveAndFlush(InboxEvent.record(eventId, eventType, aggregateId));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
