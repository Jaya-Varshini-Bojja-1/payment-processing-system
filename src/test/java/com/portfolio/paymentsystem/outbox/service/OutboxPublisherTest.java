package com.portfolio.paymentsystem.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentsystem.outbox.domain.OutboxEvent;
import com.portfolio.paymentsystem.outbox.domain.OutboxStatus;
import com.portfolio.paymentsystem.outbox.repository.OutboxEventRepository;

/**
 * Unit tests for the publisher's claim/send/retry/exhaustion decisions,
 * with Kafka and the transaction manager both mocked — the same style as
 * {@code IdempotencyServiceTest}: {@link PlatformTransactionManager} is
 * mocked to invoke each {@code TransactionTemplate} callback transparently,
 * so what is under test is purely "given this send outcome, does the row
 * end up in the right state," not real transactional semantics (that is
 * {@code OutboxPublisherKafkaIntegrationTest}'s job, against a real broker).
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final String TOPIC = "transfers.completed";
    private static final String DLQ_TOPIC = "transfers.dlq";

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    // findAndRegisterModules() mirrors what Spring Boot's autoconfigured
    // ObjectMapper bean does for us in production (registers jackson-datatype-jsr310
    // for Instant, among others) — a plain `new ObjectMapper()` here would silently
    // fail to serialize DlqEnvelope.failedAt and mask real behavior under test.
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private OutboxProperties outboxProperties;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        outboxProperties = new OutboxProperties();
        outboxProperties.setMaxAttempts(3);
        publisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, outboxProperties, objectMapper, transactionManager);
    }

    private OutboxEvent newPendingEvent() {
        return OutboxEvent.create(
                UUID.randomUUID(), "Transfer", UUID.randomUUID(), "TransferCompleted",
                TOPIC, DLQ_TOPIC, "{\"eventType\":\"TransferCompleted\"}");
    }

    private void stubClaim(OutboxEvent event) {
        when(outboxEventRepository.lockDueForPublishing(any(), any(), anyInt())).thenReturn(List.of(event));
        when(outboxEventRepository.findById(event.getId())).thenReturn(Optional.of(event));
    }

    @SuppressWarnings("unchecked")
    private void stubSendSuccess(String topic) {
        when(kafkaTemplate.send(eq(topic), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private void stubSendFailure(String topic) {
        when(kafkaTemplate.send(eq(topic), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));
    }

    @Test
    void claimingMarksTheRowPublishingBeforeAnySendIsAttempted() {
        OutboxEvent event = newPendingEvent();
        stubClaim(event);
        stubSendSuccess(TOPIC);

        publisher.publishDueEvents();

        // Verified indirectly: the row transitioned through PUBLISHING (not
        // straight PENDING -> PUBLISHED) is implied by markPublishing() being
        // the only path that clears next_attempt_at's "due" meaning; the
        // directly observable, durable claim is its final state below.
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void successfulSendMarksTheClaimedRowPublished() {
        OutboxEvent event = newPendingEvent();
        stubClaim(event);
        stubSendSuccess(TOPIC);

        publisher.publishDueEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        verify(kafkaTemplate).send(TOPIC, event.getAggregateId().toString(), event.getPayloadJson());
        verify(kafkaTemplate, never()).send(eq(DLQ_TOPIC), anyString(), anyString());
    }

    @Test
    void sendFailureBelowMaxAttemptsSchedulesARetryInsteadOfFailing() {
        OutboxEvent event = newPendingEvent();
        stubClaim(event);
        stubSendFailure(TOPIC);

        publisher.publishDueEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).contains("broker unavailable");
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
        verify(kafkaTemplate, never()).send(eq(DLQ_TOPIC), anyString(), anyString());
    }

    @Test
    void sendFailureAtMaxAttemptsMarksFailedAndPublishesToTheDlqTopicTaggedAsPublishFailed() throws Exception {
        OutboxEvent event = newPendingEvent();
        // Simulate two prior failed attempts via the same public API a real
        // failure would use, so attemptCount already sits at maxAttempts - 1.
        event.scheduleRetry("first failure", Instant.now());
        event.markPublishing();
        event.scheduleRetry("second failure", Instant.now());
        assertThat(event.getAttemptCount()).isEqualTo(2);

        stubClaim(event);
        stubSendFailure(TOPIC);
        stubSendSuccess(DLQ_TOPIC);

        publisher.publishDueEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(3);

        verify(kafkaTemplate, times(1)).send(eq(DLQ_TOPIC), eq(event.getAggregateId().toString()), anyString());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(DLQ_TOPIC), anyString(), captor.capture());
        JsonNode dlqMessage = objectMapper.readTree(captor.getValue());
        assertThat(dlqMessage.get("failureStage").asText()).isEqualTo("PUBLISH_FAILED");
        assertThat(dlqMessage.get("eventId").asText()).isEqualTo(event.getId().toString());
        assertThat(dlqMessage.get("eventType").asText()).isEqualTo("TransferCompleted");
        assertThat(dlqMessage.get("originalTopic").asText()).isEqualTo(TOPIC);
        assertThat(dlqMessage.get("originalPayloadJson").asText()).isEqualTo(event.getPayloadJson());
    }

    @Test
    void dlqPublishFailureDoesNotThrowAndLeavesTheRowFailed() {
        OutboxEvent event = newPendingEvent();
        event.scheduleRetry("first failure", Instant.now());
        event.markPublishing();
        event.scheduleRetry("second failure", Instant.now());

        stubClaim(event);
        stubSendFailure(TOPIC);
        stubSendFailure(DLQ_TOPIC);

        publisher.publishDueEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    void noDueRowsMeansNoKafkaInteractionAtAll() {
        when(outboxEventRepository.lockDueForPublishing(any(), any(), anyInt())).thenReturn(List.of());

        publisher.publishDueEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }
}
