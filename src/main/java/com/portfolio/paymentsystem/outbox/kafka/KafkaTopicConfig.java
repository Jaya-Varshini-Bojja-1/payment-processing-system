package com.portfolio.paymentsystem.outbox.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.portfolio.paymentsystem.outbox.service.OutboxProperties;

/**
 * Declares the two topics M4 uses so they exist with a known shape
 * (partition/replication) as soon as the app starts, rather than relying on
 * broker auto-create defaults. {@link org.springframework.kafka.core.KafkaAdmin}
 * (auto-configured by Spring Boot from {@code spring.kafka.*}) only creates
 * topics that do not already exist, so this is safe to run every startup.
 * <p>
 * Single partition / single replica: correct for one local KRaft broker.
 * Increasing partitions later is additive (more parallelism for consumers),
 * not a breaking change to this config.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic transfersCompletedTopic(OutboxProperties outboxProperties) {
        return new NewTopic(outboxProperties.getTransferCompletedTopic(), 1, (short) 1);
    }

    @Bean
    public NewTopic transfersDlqTopic(OutboxProperties outboxProperties) {
        return new NewTopic(outboxProperties.getTransferDlqTopic(), 1, (short) 1);
    }

    @Bean
    public NewTopic paymentsProcessTopic(OutboxProperties outboxProperties) {
        return new NewTopic(outboxProperties.getPaymentProcessingTopic(), 1, (short) 1);
    }

    @Bean
    public NewTopic paymentsDlqTopic(OutboxProperties outboxProperties) {
        return new NewTopic(outboxProperties.getPaymentDlqTopic(), 1, (short) 1);
    }
}
