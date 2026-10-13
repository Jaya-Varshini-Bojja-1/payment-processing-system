package com.portfolio.paymentsystem.outbox.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentsystem.outbox.domain.OutboxEvent;
import com.portfolio.paymentsystem.outbox.event.DlqEnvelope;
import com.portfolio.paymentsystem.outbox.event.DlqFailureStage;
import com.portfolio.paymentsystem.outbox.repository.OutboxEventRepository;

/**
 * The only thing in this application that reads {@code outbox_events} and
 * calls Kafka on the way out — the piece that turns "durably recorded
 * intent" into "actually delivered." Everything it does is generic over
 * event type: it reads {@code topic}/{@code payloadJson} off the row and
 * has no idea "TransferCompleted" exists.
 *
 * <h2>Why claim and send are separate transactions</h2>
 * Claiming (SELECT ... FOR UPDATE SKIP LOCKED, mark PUBLISHING, commit) is
 * deliberately a short, separate {@code REQUIRES_NEW} transaction from the
 * Kafka send that follows it — exactly the split {@code IdempotencyService}
 * uses for the same reason: a network call to a broker can be slow or hang,
 * and holding a Postgres row lock for that whole duration would let one
 * slow send stall every other publisher tick (and, if this process ever
 * runs with multiple instances, every other instance's claim query too).
 * Marking the row PUBLISHED (or scheduling its retry / marking it FAILED)
 * is, in turn, its own fresh transaction after the send settles.
 *
 * <h2>What "at least once" means here</h2>
 * If this process crashes after {@code kafkaTemplate.send(...)} returns
 * but before the follow-up transaction commits {@code PUBLISHED}, the row
 * is reclaimed (via {@code next_attempt_at}) and sent again next tick — a
 * real, duplicate Kafka message. That is expected and is exactly why the
 * consumer (see the {@code transfer.event} listener) must be idempotent;
 * this class does not try to prevent that duplicate, only to guarantee the
 * event is never silently dropped.
 */
@Service
@ConditionalOnProperty(prefix = "app.outbox", name = "publisher-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties outboxProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTransaction;

    public OutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            OutboxProperties outboxProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.outboxProperties = outboxProperties;
        this.objectMapper = objectMapper;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    public void poll() {
        if (!outboxProperties.isSchedulerEnabled()) {
            return;
        }
        publishDueEvents();
    }

    /**
     * Public (not just the {@code @Scheduled} entry point) so tests can
     * invoke exactly one publisher tick deterministically instead of
     * waiting on a timer — the same reasoning as calling
     * {@code TransferExecutionService#attempt} directly in its tests
     * rather than going through HTTP.
     */
    public void publishDueEvents() {
        List<OutboxEvent> claimed = claimBatch();
        for (OutboxEvent event : claimed) {
            publishOne(event);
        }
    }

    private List<OutboxEvent> claimBatch() {
        return requiresNewTransaction.execute(status -> {
            Instant now = Instant.now();
            Instant staleBefore = now.minus(outboxProperties.getStalePublishingTimeout());
            List<OutboxEvent> due = outboxEventRepository.lockDueForPublishing(
                    now, staleBefore, outboxProperties.getPublishBatchSize());
            due.forEach(OutboxEvent::markPublishing);
            return due;
        });
    }

    private void publishOne(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayloadJson())
                    .get(outboxProperties.getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            markPublished(event.getId());
            log.info("Published outbox event id={} eventType={} topic={}",
                    event.getId(), event.getEventType(), event.getTopic());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleSendFailure(event, e);
        } catch (ExecutionException | TimeoutException e) {
            handleSendFailure(event, e);
        }
    }

    private void markPublished(UUID eventId) {
        requiresNewTransaction.executeWithoutResult(status ->
                outboxEventRepository.findById(eventId).ifPresent(OutboxEvent::markPublished));
    }

    /** @return true if this failure exhausted the row's attempts (i.e. it is now FAILED and needs a DLQ publish). */
    private void handleSendFailure(OutboxEvent claimedSnapshot, Exception cause) {
        String error = describe(cause);
        Boolean exhausted = requiresNewTransaction.execute(status -> {
            OutboxEvent event = outboxEventRepository.findById(claimedSnapshot.getId()).orElse(null);
            if (event == null) {
                return false;
            }
            int attemptsIncludingThisOne = event.getAttemptCount() + 1;
            if (attemptsIncludingThisOne >= outboxProperties.getMaxAttempts()) {
                event.markExhausted(error);
                return true;
            }
            event.scheduleRetry(error, Instant.now().plus(backoffAfter(attemptsIncludingThisOne)));
            return false;
        });

        log.warn("Publish attempt failed for outbox event id={} topic={} exhausted={}",
                claimedSnapshot.getId(), claimedSnapshot.getTopic(), exhausted, cause);

        if (Boolean.TRUE.equals(exhausted)) {
            publishToDlq(claimedSnapshot, error);
        }
    }

    private Duration backoffAfter(int attemptsIncludingThisOne) {
        double factor = Math.pow(outboxProperties.getBackoffMultiplier(), attemptsIncludingThisOne - 1);
        return Duration.ofMillis((long) (outboxProperties.getInitialBackoff().toMillis() * factor));
    }

    /**
     * Best-effort: if the DLQ publish itself fails (most likely because
     * Kafka is unreachable — the same reason the original send just
     * exhausted its retries), we do not escalate further. The row is
     * already durably {@code FAILED} in Postgres; that row, not the DLQ
     * topic, is the source of truth an operator or a future replay tool
     * would use to recover this event.
     */
    private void publishToDlq(OutboxEvent event, String reason) {
        DlqEnvelope dlqEnvelope = new DlqEnvelope(
                DlqFailureStage.PUBLISH_FAILED,
                event.getId(), event.getEventType(), event.getAggregateType(), event.getAggregateId(),
                event.getTopic(), event.getAttemptCount() + 1, reason, Instant.now(), event.getPayloadJson());
        try {
            String dlqJson = objectMapper.writeValueAsString(dlqEnvelope);
            kafkaTemplate.send(event.getDlqTopic(), event.getAggregateId().toString(), dlqJson)
                    .get(outboxProperties.getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception dlqFailure) {
            log.error("Failed to publish exhausted outbox event id={} to DLQ topic={}; it remains FAILED in outbox_events",
                    event.getId(), event.getDlqTopic(), dlqFailure);
        }
    }

    private String describe(Exception cause) {
        Throwable root = cause.getCause() != null ? cause.getCause() : cause;
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
