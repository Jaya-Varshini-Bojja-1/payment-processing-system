package com.portfolio.paymentsystem.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.portfolio.paymentsystem.outbox.domain.OutboxEvent;
import com.portfolio.paymentsystem.outbox.domain.OutboxStatus;
import com.portfolio.paymentsystem.outbox.repository.OutboxEventRepository;

/**
 * Proves the publisher against a real, host-local Kafka broker (KRaft, no
 * ZooKeeper, no Testcontainers) and real PostgreSQL: a {@code PENDING} row
 * genuinely becomes a Kafka message an independent consumer can read, and
 * the row is marked {@code PUBLISHED} only after that real delivery.
 * <p>
 * {@code app.outbox.publisher-enabled=true} overrides the test-classpath
 * default of {@code false} (see {@code src/test/resources/application.yml})
 * so the {@code OutboxPublisher} bean exists to autowire here — this is the
 * one test class in M4 that is allowed to depend on Kafka being up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "app.outbox.publisher-enabled=true",
        // Keep the bean (so publishDueEvents() can be called manually
        // below) but disable its @Scheduled background tick — otherwise
        // it races this test's own deterministic call for the exact rows
        // under test (and drains unrelated leftover rows from other test
        // classes sharing the same real Postgres database).
        "app.outbox.scheduler-enabled=false"
})
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
@EnabledIfEnvironmentVariable(named = "KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
class OutboxPublisherKafkaIntegrationTest {

    @Autowired
    private OutboxPublisher outboxPublisher;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private KafkaConsumer<String, String> rawConsumer;

    /**
     * A plain, hand-built consumer on its own random group id — deliberately
     * NOT the application's {@code @KafkaListener} (that is step 4's job to
     * prove end to end). This is here purely to observe, independently of
     * this application's own code, that a real message with the expected
     * key/value actually reached the real topic.
     */
    @BeforeEach
    void subscribeRawConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-publisher-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        rawConsumer = new KafkaConsumer<>(props);
        rawConsumer.subscribe(List.of("transfers.completed"));
        // Force partition assignment before this test produces anything, so
        // the earliest-offset subscription doesn't miss the message due to
        // consumer-group-join lag racing the publisher's send below.
        rawConsumer.poll(Duration.ofSeconds(2));
    }

    @AfterEach
    void closeRawConsumer() {
        if (rawConsumer != null) {
            rawConsumer.close();
        }
    }

    @Test
    void publishDueEventsDeliversAPendingRowToKafkaAndMarksItPublished() {
        UUID aggregateId = UUID.randomUUID();
        String payloadJson = "{\"eventType\":\"TransferCompleted\",\"aggregateId\":\"" + aggregateId + "\"}";
        OutboxEvent event = outboxEventRepository.save(OutboxEvent.create(
                UUID.randomUUID(), "Transfer", aggregateId, "TransferCompleted",
                "transfers.completed", "transfers.dlq", payloadJson));

        deferOtherDueOutboxRows(event.getId());
        outboxPublisher.publishDueEvents();

        OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(reloaded.getPublishedAt()).isNotNull();

        ConsumerRecord<String, String> received = pollForRecordWithKey(aggregateId.toString());
        assertThat(received.key()).isEqualTo(aggregateId.toString());
        assertThat(received.value()).isEqualTo(payloadJson);
    }

    /**
     * Simulates the crash this whole design exists to survive: a previous
     * publisher instance sent (or was sending) this row and died before
     * marking it {@code PUBLISHED}. Seeded directly via SQL — bypassing
     * {@code OutboxEvent.markPublishing()}, which always stamps "now" — so
     * the row looks exactly like it has been stuck since well before the
     * stale-publishing timeout, the only way to genuinely exercise
     * {@code OutboxEventRepository#lockDueForPublishing}'s stale-reclaim
     * branch rather than its normal due-PENDING branch.
     */
    @Test
    void stalePublishingRowIsReclaimedAndRepublishedOnTheNextPoll() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String payloadJson = "{\"eventType\":\"TransferCompleted\",\"aggregateId\":\"" + aggregateId + "\"}";
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));

        jdbcTemplate.update("""
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, topic, dlq_topic, payload_json,
                     status, attempt_count, next_attempt_at, created_at)
                VALUES (?, 'Transfer', ?, 'TransferCompleted', 'transfers.completed', 'transfers.dlq', ?,
                        'PUBLISHING', 0, ?, ?)
                """, eventId, aggregateId, payloadJson, java.sql.Timestamp.from(longAgo), java.sql.Timestamp.from(longAgo));

        deferOtherDueOutboxRows(eventId);
        outboxPublisher.publishDueEvents();

        OutboxEvent reloaded = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

        ConsumerRecord<String, String> received = pollForRecordWithKey(aggregateId.toString());
        assertThat(received.value()).isEqualTo(payloadJson);
    }

    /**
     * Other @SpringBootTest classes share this host Postgres and leave
     * PENDING / stale-PUBLISHING rows behind (they run with the publisher
     * bean off). {@code publishDueEvents()} claims {@code LIMIT 20} oldest
     * due rows, so a backlog of 20+ would starve the row this test just
     * inserted. Push everyone else an hour into the future so this tick
     * can only see the row under test.
     */
    private void deferOtherDueOutboxRows(UUID eventId) {
        jdbcTemplate.update("""
                UPDATE outbox_events
                   SET next_attempt_at = ?
                 WHERE id <> ?
                   AND status IN ('PENDING', 'PUBLISHING')
                """,
                java.sql.Timestamp.from(Instant.now().plus(Duration.ofHours(1))),
                eventId);
    }

    private ConsumerRecord<String, String> pollForRecordWithKey(String key) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = rawConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError("No transfers.completed record with key=" + key + " observed within timeout");
    }
}
