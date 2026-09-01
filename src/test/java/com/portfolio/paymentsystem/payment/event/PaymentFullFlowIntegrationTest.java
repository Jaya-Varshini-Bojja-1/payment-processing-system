package com.portfolio.paymentsystem.payment.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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

import com.portfolio.paymentsystem.identity.domain.User;
import com.portfolio.paymentsystem.identity.repository.UserRepository;
import com.portfolio.paymentsystem.outbox.service.OutboxPublisher;
import com.portfolio.paymentsystem.payment.dto.CreatePaymentRequest;
import com.portfolio.paymentsystem.payment.service.PaymentService;
import com.portfolio.paymentsystem.wallet.domain.Account;
import com.portfolio.paymentsystem.wallet.repository.AccountRepository;

/**
 * The end-to-end proof of M5's core claim, against real, host-local
 * PostgreSQL, Redis, and Kafka (no mocks, no Testcontainers): a deposit or
 * withdrawal accepted through {@link PaymentService} genuinely flows
 * through the outbox, a real Kafka broker, {@link PaymentProcessingConsumer},
 * the simulated gateway, and out the other side as posted ledger entries --
 * for all four deterministic gateway scenarios (immediate success,
 * retry-then-succeed, retry-then-exhaust, immediate decline).
 * <p>
 * {@code OutboxPublisher#publishDueEvents} is called directly rather than
 * waiting on its {@code @Scheduled} loop, purely so this test is fast and
 * deterministic instead of racing a multi-second poll interval -- the
 * publisher itself is already proven independently by
 * {@code OutboxPublisherKafkaIntegrationTest}. The retry backoff is
 * shortened via {@code @TestPropertySource} for the same reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "app.outbox.publisher-enabled=true",
        "spring.kafka.listener.auto-startup=true",
        "app.payment.max-gateway-attempts=2",
        "app.payment.retry-initial-backoff=PT0.2S",
        "app.payment.retry-backoff-multiplier=1.0"
})
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
@EnabledIfEnvironmentVariable(named = "KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
class PaymentFullFlowIntegrationTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private OutboxPublisher outboxPublisher;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private KafkaConsumer<String, String> dlqConsumer;

    @BeforeEach
    void subscribeDlqConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-full-flow-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        dlqConsumer = new KafkaConsumer<>(props);
        dlqConsumer.subscribe(List.of("payments.dlq"));
        dlqConsumer.poll(Duration.ofSeconds(2));
    }

    @AfterEach
    void closeDlqConsumer() {
        if (dlqConsumer != null) {
            dlqConsumer.close();
        }
    }

    private UUID createFundedUserAndAccount(long balancePaise) {
        User user = userRepository.save(User.newUser("payment-flow-it-" + UUID.randomUUID() + "@example.com", "unused-hash"));
        Account account = accountRepository.save(Account.openInr(user.getId()));
        if (balancePaise > 0) {
            jdbcTemplate.update("UPDATE accounts SET balance_paise = ? WHERE id = ?", balancePaise, account.getId());
        }
        return user.getId();
    }

    private long balanceOf(UUID accountId) {
        return jdbcTemplate.queryForObject("SELECT balance_paise FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private UUID accountIdOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT id FROM accounts WHERE user_id = ?", UUID.class, userId);
    }

    /** Drives the outbox and polls the DB, so this works whether the payment resolves in one attempt or several. */
    private String awaitTerminalStatus(UUID paymentId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            outboxPublisher.publishDueEvents();
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM payment_transactions WHERE id = ?", String.class, paymentId);
            if (List.of("SUCCEEDED", "FAILED", "REQUIRES_MANUAL_REVIEW").contains(status)) {
                return status;
            }
            sleep(150);
        }
        throw new AssertionError("PaymentTransaction " + paymentId + " did not reach a terminal status within timeout");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void depositInTheSuccessBucketSettlesAndCreditsTheAccount() {
        UUID userId = createFundedUserAndAccount(0);
        UUID accountId = accountIdOf(userId);

        var outcome = paymentService.createDeposit(userId, "flow-key-" + UUID.randomUUID(), new CreatePaymentRequest(100_050));
        String status = awaitTerminalStatus(outcome.response().id());

        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat(balanceOf(accountId)).isEqualTo(100_050L);
    }

    @Test
    void withdrawalInTheSuccessBucketSettlesAndDebitsTheAccount() {
        UUID userId = createFundedUserAndAccount(500_000);
        UUID accountId = accountIdOf(userId);

        var outcome = paymentService.createWithdrawal(userId, "flow-key-" + UUID.randomUUID(), new CreatePaymentRequest(100_010));
        String status = awaitTerminalStatus(outcome.response().id());

        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat(balanceOf(accountId)).isEqualTo(400_000L);
    }

    @Test
    void depositInTheRetrySucceedsBucketFailsOnceThenSettlesOnRetry() {
        // 80 <= bucket < 90
        UUID userId = createFundedUserAndAccount(0);
        UUID accountId = accountIdOf(userId);

        var outcome = paymentService.createDeposit(userId, "flow-key-" + UUID.randomUUID(), new CreatePaymentRequest(100_085));
        String status = awaitTerminalStatus(outcome.response().id());

        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat(balanceOf(accountId)).isEqualTo(100_085L);

        Integer attemptCount = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM payment_transactions WHERE id = ?", Integer.class, outcome.response().id());
        assertThat(attemptCount).isEqualTo(1);
    }

    @Test
    void depositInTheAlwaysRetryableBucketExhaustsIntoManualReviewAndReachesTheDlq() throws Exception {
        // 90 <= bucket < 95; app.payment.max-gateway-attempts=2 for this test class
        UUID userId = createFundedUserAndAccount(0);
        UUID accountId = accountIdOf(userId);

        var outcome = paymentService.createDeposit(userId, "flow-key-" + UUID.randomUUID(), new CreatePaymentRequest(100_092));
        String status = awaitTerminalStatus(outcome.response().id());

        assertThat(status).isEqualTo("REQUIRES_MANUAL_REVIEW");
        assertThat(balanceOf(accountId)).isEqualTo(0L);

        ConsumerRecord<String, String> dlqRecord = pollForRecordContaining(outcome.response().id().toString());
        assertThat(dlqRecord.value()).contains("PaymentProcessingExhausted");
    }

    @Test
    void withdrawalInTheNonRetryableBucketDeclinesImmediatelyWithoutTouchingTheLedger() {
        // 95 <= bucket < 100
        UUID userId = createFundedUserAndAccount(500_000);
        UUID accountId = accountIdOf(userId);

        var outcome = paymentService.createWithdrawal(userId, "flow-key-" + UUID.randomUUID(), new CreatePaymentRequest(100_097));
        String status = awaitTerminalStatus(outcome.response().id());

        assertThat(status).isEqualTo("FAILED");
        assertThat(balanceOf(accountId)).isEqualTo(500_000L);
    }

    private ConsumerRecord<String, String> pollForRecordContaining(String marker) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null && record.value().contains(marker)) {
                    return record;
                }
            }
        }
        throw new AssertionError("No payments.dlq record containing marker=" + marker + " observed within timeout");
    }
}
