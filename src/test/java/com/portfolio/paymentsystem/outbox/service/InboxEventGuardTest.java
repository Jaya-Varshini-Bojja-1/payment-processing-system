package com.portfolio.paymentsystem.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.portfolio.paymentsystem.outbox.domain.InboxEvent;
import com.portfolio.paymentsystem.outbox.repository.InboxEventRepository;

/**
 * Unit tests for the "insert or detect duplicate" logic — the actual
 * transactional/at-least-once redelivery proof lives in
 * {@code TransferCompletedConsumerKafkaIntegrationTest} against a real
 * broker; this is just "does the row-already-exists case return the right
 * boolean instead of throwing."
 */
@ExtendWith(MockitoExtension.class)
class InboxEventGuardTest {

    @Mock
    private InboxEventRepository inboxEventRepository;

    private InboxEventGuard inboxEventGuard;

    @Test
    void newEventIsRecordedAndReturnsTrue() {
        inboxEventGuard = new InboxEventGuard(inboxEventRepository);
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        boolean isNew = inboxEventGuard.recordIfNew(eventId, "TransferCompleted", aggregateId);

        assertThat(isNew).isTrue();
        verify(inboxEventRepository).saveAndFlush(any(InboxEvent.class));
    }

    @Test
    void duplicateEventIdReturnsFalseInsteadOfThrowing() {
        inboxEventGuard = new InboxEventGuard(inboxEventRepository);
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(inboxEventRepository).saveAndFlush(any(InboxEvent.class));

        boolean isNew = inboxEventGuard.recordIfNew(UUID.randomUUID(), "TransferCompleted", UUID.randomUUID());

        assertThat(isNew).isFalse();
    }
}
