package com.portfolio.paymentsystem.payment.event;

import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.portfolio.paymentsystem.gateway.GatewayRequest;
import com.portfolio.paymentsystem.gateway.GatewayResult;
import com.portfolio.paymentsystem.gateway.SimulatedPaymentGateway;
import com.portfolio.paymentsystem.ledger.exception.DuplicateLedgerPostingException;
import com.portfolio.paymentsystem.outbox.event.OutboxEventEnvelope;
import com.portfolio.paymentsystem.outbox.event.OutboxEventEnvelopeReader;
import com.portfolio.paymentsystem.payment.exception.DuplicatePaymentEventException;
import com.portfolio.paymentsystem.payment.service.PaymentOutcomeService;

/**
 * The consumer half of M5's one processing topic. Unlike
 * {@code TransferCompletedListener} (which reacts to money that already
 * moved), this listener is where the money movement actually gets decided:
 * it calls the gateway and then applies whatever it says.
 * <p>
 * The gateway call sits deliberately between two service calls and inside
 * <b>no</b> transaction of its own -- {@link SimulatedPaymentGateway#process}
 * is this method's only non-database work, and per the M5 design it must
 * never happen while a Postgres transaction is open.
 */
@Component
public class PaymentProcessingConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingConsumer.class);

    private final OutboxEventEnvelopeReader envelopeReader;
    private final PaymentOutcomeService paymentOutcomeService;
    private final SimulatedPaymentGateway gateway;

    public PaymentProcessingConsumer(
            OutboxEventEnvelopeReader envelopeReader, PaymentOutcomeService paymentOutcomeService, SimulatedPaymentGateway gateway) {
        this.envelopeReader = envelopeReader;
        this.paymentOutcomeService = paymentOutcomeService;
        this.gateway = gateway;
    }

    @KafkaListener(topics = "${app.outbox.payment-processing-topic}")
    public void onPaymentProcessingRequested(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        // Malformed envelope -> IllegalArgumentException here, treated as a
        // poison message by Spring's error handler (retry, then CONSUME_FAILED
        // DLQ), same as TransferCompletedListener.
        OutboxEventEnvelope<PaymentProcessingRequestedPayload> envelope =
                envelopeReader.read(record.value(), PaymentProcessingRequestedPayload.class);
        UUID paymentTransactionId = envelope.payload().paymentTransactionId();

        if (paymentOutcomeService.isTerminal(paymentTransactionId)) {
            log.info("Skipping PaymentProcessingRequested for already-terminal paymentTransactionId={} eventId={}",
                    paymentTransactionId, envelope.eventId());
            acknowledgment.acknowledge();
            return;
        }

        paymentOutcomeService.markProcessingIfPossible(paymentTransactionId);

        GatewayRequest gatewayRequest = new GatewayRequest(
                paymentTransactionId, envelope.payload().amountPaise(), envelope.payload().attemptNumber());
        GatewayResult result = gateway.process(gatewayRequest);
        log.info("Gateway outcome={} paymentTransactionId={} attemptNumber={} eventId={}",
                result.outcome(), paymentTransactionId, envelope.payload().attemptNumber(), envelope.eventId());

        try {
            paymentOutcomeService.applyOutcome(envelope.eventId(), envelope.eventType(), paymentTransactionId, result);
        } catch (DuplicatePaymentEventException | DuplicateLedgerPostingException alreadyHandled) {
            // Either this exact message was already applied by an earlier
            // delivery, or a concurrent attempt for the same payment won the
            // race to post its ledger entries first -- both mean the
            // authoritative outcome is already durable somewhere else, so
            // there is nothing left for this delivery to do but ack.
            log.info("Not re-applying outcome for paymentTransactionId={} eventId={}: {}",
                    paymentTransactionId, envelope.eventId(), alreadyHandled.getMessage());
        }

        acknowledgment.acknowledge();
    }
}
