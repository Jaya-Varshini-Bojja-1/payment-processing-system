package com.portfolio.paymentsystem.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.paymentsystem.identity.domain.User;
import com.portfolio.paymentsystem.identity.repository.UserRepository;
import com.portfolio.paymentsystem.payment.dto.CreatePaymentRequest;
import com.portfolio.paymentsystem.payment.exception.InsufficientBalanceException;
import com.portfolio.paymentsystem.wallet.domain.Account;
import com.portfolio.paymentsystem.wallet.repository.AccountRepository;

/**
 * Proves M5's dual-write guard against real, host-local PostgreSQL (no
 * Kafka, no mocks) -- the same claim {@code TransferOutboxIntegrationTest}
 * proves for M4: a durably accepted payment's dispatch event commits in
 * the SAME transaction as the {@code PaymentTransaction} row, and a
 * rejected request leaves no trace of either.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
class PaymentOutboxIntegrationTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private record UserAndAccount(UUID userId, UUID accountId) {
    }

    private UserAndAccount createUserAndAccount() {
        User user = userRepository.save(User.newUser("payment-outbox-it-" + UUID.randomUUID() + "@example.com", "unused-hash"));
        Account account = accountRepository.save(Account.openInr(user.getId()));
        return new UserAndAccount(user.getId(), account.getId());
    }

    private void fund(UUID accountId, long balancePaise) {
        jdbcTemplate.update("UPDATE accounts SET balance_paise = ? WHERE id = ?", balancePaise, accountId);
    }

    @Test
    void acceptedDepositWritesExactlyOnePendingProcessingRequestedOutboxRowInTheSameCommit() throws Exception {
        UserAndAccount user = createUserAndAccount();

        var outcome = paymentService.createDeposit(user.userId(), "outbox-key-" + UUID.randomUUID(), new CreatePaymentRequest(50_000));
        UUID paymentId = outcome.response().id();

        var row = jdbcTemplate.queryForMap(
                "SELECT event_type, topic, dlq_topic, status, payload_json FROM outbox_events WHERE aggregate_type = 'PaymentTransaction' AND aggregate_id = ?",
                paymentId);

        assertThat(row.get("event_type")).isEqualTo("PaymentProcessingRequested:0");
        assertThat(row.get("topic")).isEqualTo("payments.process");
        assertThat(row.get("dlq_topic")).isEqualTo("payments.dlq");
        assertThat(row.get("status")).isEqualTo("PENDING");

        JsonNode envelope = objectMapper.readTree((String) row.get("payload_json"));
        assertThat(envelope.get("eventType").asText()).isEqualTo("PaymentProcessingRequested:0");
        assertThat(envelope.get("aggregateType").asText()).isEqualTo("PaymentTransaction");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(paymentId.toString());

        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("paymentTransactionId").asText()).isEqualTo(paymentId.toString());
        assertThat(payload.get("type").asText()).isEqualTo("DEPOSIT");
        assertThat(payload.get("amountPaise").asLong()).isEqualTo(50_000L);
        assertThat(payload.get("attemptNumber").asInt()).isZero();

        Integer paymentRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions WHERE id = ? AND status = 'PENDING'", Integer.class, paymentId);
        assertThat(paymentRowCount).isEqualTo(1);
    }

    @Test
    void rejectedWithdrawalLeavesNoPaymentOrOutboxTrace() {
        UserAndAccount user = createUserAndAccount();
        fund(user.accountId(), 1_000);

        // Every outbox row for this aggregate type is only ever written
        // alongside a real PaymentTransaction id (proven by the test above),
        // so a before/after count on the shared, non-transactional table is
        // enough here -- there is no accountId embedded in the payload to
        // search by directly (unlike Transfer's payload).
        Integer outboxCountBefore = countPaymentOutboxRows();

        assertThatThrownBy(() -> paymentService.createWithdrawal(
                user.userId(), "outbox-key-" + UUID.randomUUID(), new CreatePaymentRequest(50_000)))
                .isInstanceOf(InsufficientBalanceException.class);

        Integer paymentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions WHERE account_id = ?", Integer.class, user.accountId());

        assertThat(paymentCount).isZero();
        assertThat(countPaymentOutboxRows()).isEqualTo(outboxCountBefore);
    }

    private Integer countPaymentOutboxRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'PaymentTransaction'", Integer.class);
    }
}
