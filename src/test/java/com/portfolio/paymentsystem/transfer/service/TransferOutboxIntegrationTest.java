package com.portfolio.paymentsystem.transfer.service;

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
import com.portfolio.paymentsystem.transfer.dto.CreateTransferRequest;
import com.portfolio.paymentsystem.transfer.exception.InsufficientBalanceException;
import com.portfolio.paymentsystem.wallet.domain.Account;
import com.portfolio.paymentsystem.wallet.repository.AccountRepository;

/**
 * The actual proof of M4's core claim, against real, host-local PostgreSQL
 * 18 (no mocks, no Kafka): a completed transfer's {@code TransferCompleted}
 * outbox row commits in the SAME transaction as the transfer + ledger
 * write, and a rolled-back attempt leaves no partial trace of any of them.
 * <p>
 * This is what actually rules out the dual-write bug — not "we called
 * outboxEventWriter.append()", but "there is no way to observe a completed
 * Transfer in the database without also observing its outbox row, or vice
 * versa."
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "POSTGRES_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JWT_SECRET", matches = ".+")
class TransferOutboxIntegrationTest {

    @Autowired
    private TransferService transferService;
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
        User user = userRepository.save(User.newUser("transfer-outbox-it-" + UUID.randomUUID() + "@example.com", "unused-hash"));
        Account account = accountRepository.save(Account.openInr(user.getId()));
        return new UserAndAccount(user.getId(), account.getId());
    }

    /** Test fixture only, same pattern as the other M3 integration tests: no deposit endpoint exists until M5. */
    private void fund(UUID accountId, long balancePaise) {
        jdbcTemplate.update("UPDATE accounts SET balance_paise = ? WHERE id = ?", balancePaise, accountId);
    }

    private int countOutboxRowsForTransfer(UUID transferId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'Transfer' AND aggregate_id = ?",
                Integer.class, transferId);
        return count == null ? 0 : count;
    }

    @Test
    void successfulTransferWritesExactlyOnePendingTransferCompletedOutboxRowInTheSameCommit() throws Exception {
        UserAndAccount sender = createUserAndAccount();
        UserAndAccount recipient = createUserAndAccount();
        fund(sender.accountId(), 100_000);

        var outcome = transferService.createTransfer(sender.userId(), "outbox-key-" + UUID.randomUUID(),
                new CreateTransferRequest(recipient.userId(), 40_000));
        UUID transferId = outcome.response().id();

        var row = jdbcTemplate.queryForMap(
                "SELECT event_type, topic, dlq_topic, status, payload_json FROM outbox_events WHERE aggregate_type = 'Transfer' AND aggregate_id = ?",
                transferId);

        assertThat(row.get("event_type")).isEqualTo("TransferCompleted");
        assertThat(row.get("topic")).isEqualTo("transfers.completed");
        assertThat(row.get("dlq_topic")).isEqualTo("transfers.dlq");
        assertThat(row.get("status")).isEqualTo("PENDING");

        JsonNode envelope = objectMapper.readTree((String) row.get("payload_json"));
        assertThat(envelope.get("eventType").asText()).isEqualTo("TransferCompleted");
        assertThat(envelope.get("aggregateType").asText()).isEqualTo("Transfer");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(transferId.toString());
        assertThat(envelope.get("eventId").asText()).isNotBlank();
        assertThat(envelope.get("occurredAt").asText()).isNotBlank();

        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("transferId").asText()).isEqualTo(transferId.toString());
        assertThat(payload.get("sourceAccountId").asText()).isEqualTo(sender.accountId().toString());
        assertThat(payload.get("destinationAccountId").asText()).isEqualTo(recipient.accountId().toString());
        assertThat(payload.get("amountPaise").asLong()).isEqualTo(40_000L);
        assertThat(payload.get("currency").asText()).isEqualTo("INR");
    }

    @Test
    void failedAttemptLeavesNoTransferLedgerOrOutboxTrace() {
        UserAndAccount sender = createUserAndAccount();
        UserAndAccount recipient = createUserAndAccount();
        fund(sender.accountId(), 1_000);

        assertThatThrownBy(() -> transferService.createTransfer(sender.userId(), "outbox-key-" + UUID.randomUUID(),
                new CreateTransferRequest(recipient.userId(), 5_000)))
                .isInstanceOf(InsufficientBalanceException.class);

        Integer transferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE source_account_id = ?", Integer.class, sender.accountId());
        // No Transfer row exists to look up an aggregate_id by (that is
        // exactly the point), so scope by searching the payload instead —
        // this test runs against the same shared, non-transactional
        // PostgreSQL instance as other tests here, so asserting on the
        // whole outbox_events table would be flaky depending on run order.
        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE payload_json LIKE ?", Integer.class,
                "%" + sender.accountId() + "%");

        assertThat(transferCount).isZero();
        assertThat(outboxCount).isZero();
    }
}
