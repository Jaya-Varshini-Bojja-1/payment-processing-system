package com.portfolio.paymentsystem.transfer.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentsystem.outbox.event.OutboxEventEnvelope;

/**
 * Proves the consumer side of M4 against the real application context, a
 * real host-local Kafka broker, and real PostgreSQL — deliberately
 * bypassing {@code OutboxPublisher} and producing directly to
 * {@code transfers.completed} with a raw producer, so what's under test is
 * only {@code TransferCompletedListener} + the inbox, not the publisher
 * (already covered by {@code OutboxPublisherKafkaIntegrationTest}).
 * <p>
 * {@code spring.kafka.listener.auto-startup=true} overrides the
 * test-classpath default of {@code false} so the real
 * {@code @KafkaListener} actually runs for this test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=true")
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
@EnabledIfEnvironmentVariable(named = "KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
class TransferCompletedConsumerKafkaIntegrationTest {

    private static final String COMPLETED_TOPIC = "transfers.completed";
    private static final String DLQ_TOPIC = "transfers.dlq";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> dlqConsumer;

    @BeforeEach
    void setUp() {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(producerProps);

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "transfer-completed-consumer-it-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        dlqConsumer = new KafkaConsumer<>(consumerProps);
        dlqConsumer.subscribe(List.of(DLQ_TOPIC));
        dlqConsumer.poll(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        producer.close();
        dlqConsumer.close();
    }

    private String envelopeJson(UUID eventId, UUID transferId) throws Exception {
        TransferCompletedPayload payload = new TransferCompletedPayload(
                transferId, UUID.randomUUID(), UUID.randomUUID(), 40_000L, "INR");
        OutboxEventEnvelope<TransferCompletedPayload> envelope = new OutboxEventEnvelope<>(
                eventId, "TransferCompleted", "Transfer", transferId, Instant.now(), payload);
        return objectMapper.writeValueAsString(envelope);
    }

    private void publish(String topic, String key, String value) throws ExecutionException, InterruptedException, TimeoutException {
        producer.send(new ProducerRecord<>(topic, key, value)).get(5, TimeUnit.SECONDS);
    }

    private int countInboxRows(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    @Test
    void redeliveredMessageIsConsumedExactlyOnceInTheInbox() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        String json = envelopeJson(eventId, transferId);

        // Same eventId published twice — a real duplicate, exactly what
        // at-least-once redelivery (or a re-sent outbox row) looks like.
        publish(COMPLETED_TOPIC, transferId.toString(), json);
        publish(COMPLETED_TOPIC, transferId.toString(), json);

        awaitInboxRow(eventId);
        // Give the (already-processed) duplicate time to arrive and be
        // skipped, then confirm it truly did not create a second row.
        Thread.sleep(2_000);

        assertThat(countInboxRows(eventId)).isEqualTo(1);
    }

    @Test
    void malformedPayloadEndsUpOnTheDlqTaggedConsumeFailedAndNeverEntersTheInbox() throws Exception {
        String correlationMarker = "malformed-" + UUID.randomUUID();
        int inboxCountBefore = totalInboxRows();

        publish(COMPLETED_TOPIC, "bad-key", correlationMarker);

        JsonNode dlqMessage = awaitDlqRecordContaining(correlationMarker);

        assertThat(dlqMessage.get("failureStage").asText()).isEqualTo("CONSUME_FAILED");
        assertThat(dlqMessage.get("originalTopic").asText()).isEqualTo(COMPLETED_TOPIC);
        assertThat(dlqMessage.get("originalPayloadJson").asText()).isEqualTo(correlationMarker);
        assertThat(dlqMessage.get("eventId").isNull()).isTrue();

        // A malformed message has no parseable eventId to look up a specific
        // row by, so the strongest available assertion is that consuming it
        // (through retries, then recovery) never grew the inbox at all.
        assertThat(totalInboxRows()).isEqualTo(inboxCountBefore);
    }

    private int totalInboxRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inbox_events", Integer.class);
        return count == null ? 0 : count;
    }

    private void awaitInboxRow(UUID eventId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (countInboxRows(eventId) >= 1) {
                return;
            }
            sleep(200);
        }
        throw new AssertionError("No inbox_events row for eventId=" + eventId + " within timeout");
    }

    private JsonNode awaitDlqRecordContaining(String marker) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null && record.value().contains(marker)) {
                    try {
                        return objectMapper.readTree(record.value());
                    } catch (Exception e) {
                        throw new AssertionError("DLQ record was not valid JSON", e);
                    }
                }
            }
        }
        throw new AssertionError("No transfers.dlq record containing marker=" + marker + " observed within timeout");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
