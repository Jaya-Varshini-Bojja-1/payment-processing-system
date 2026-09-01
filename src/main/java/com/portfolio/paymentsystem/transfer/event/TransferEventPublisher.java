package com.portfolio.paymentsystem.transfer.event;

import org.springframework.stereotype.Service;

import com.portfolio.paymentsystem.outbox.service.OutboxEventWriter;
import com.portfolio.paymentsystem.outbox.service.OutboxProperties;
import com.portfolio.paymentsystem.transfer.domain.Transfer;

/**
 * The transfer module's only touchpoint with the outbox: it knows
 * <i>that</i> a completed transfer should become a {@code TransferCompleted}
 * event and <i>where</i> that event goes, but delegates the actual durable
 * write to the generic {@link OutboxEventWriter}. Called from
 * {@code TransferExecutionService#attempt}, inside that method's existing
 * transaction — never on its own.
 */
@Service
public class TransferEventPublisher {

    static final String AGGREGATE_TYPE = "Transfer";
    static final String EVENT_TYPE = "TransferCompleted";

    private final OutboxEventWriter outboxEventWriter;
    private final OutboxProperties outboxProperties;

    public TransferEventPublisher(OutboxEventWriter outboxEventWriter, OutboxProperties outboxProperties) {
        this.outboxEventWriter = outboxEventWriter;
        this.outboxProperties = outboxProperties;
    }

    public void publishCompleted(Transfer transfer) {
        TransferCompletedPayload payload = new TransferCompletedPayload(
                transfer.getId(),
                transfer.getSourceAccountId(),
                transfer.getDestinationAccountId(),
                transfer.getAmountPaise(),
                transfer.getCurrency().name());

        outboxEventWriter.append(
                AGGREGATE_TYPE, transfer.getId(), EVENT_TYPE,
                outboxProperties.getTransferCompletedTopic(), outboxProperties.getTransferDlqTopic(),
                payload);
    }
}
