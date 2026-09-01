package com.portfolio.paymentsystem.outbox.kafka;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.portfolio.paymentsystem.outbox.service.OutboxProperties;

/**
 * Builds the listener container factory on top of Spring Boot's
 * {@code spring.kafka.consumer.*} / {@code spring.kafka.listener.*}
 * auto-configuration (via {@link ConcurrentKafkaListenerContainerFactoryConfigurer},
 * which is exactly how Boot itself would build the default factory) rather
 * than replacing it outright, so YAML-driven settings like
 * {@code ack-mode: manual_immediate} still apply. The only thing added here
 * is the retry-then-DLQ error handler.
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaListenerErrorHandler(
            ConsumeFailureDlqPublisher consumeFailureDlqPublisher, OutboxProperties outboxProperties) {
        // FixedBackOff's second argument is retries AFTER the first attempt,
        // so total attempts = consumerMaxAttempts.
        FixedBackOff backOff = new FixedBackOff(
                outboxProperties.getConsumerRetryBackoff().toMillis(), outboxProperties.getConsumerMaxAttempts() - 1L);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(consumeFailureDlqPublisher, backOff);
        // So ConsumeFailureDlqPublisher can report a real attempt count
        // instead of always guessing outboxProperties.getConsumerMaxAttempts().
        errorHandler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return errorHandler;
    }

    /**
     * Generic over {@code Object} (not {@code String}) purely because
     * {@link ConcurrentKafkaListenerContainerFactoryConfigurer#configure}
     * requires that exact signature — the actual (de)serializers, and
     * therefore what type {@code @KafkaListener} methods receive, are still
     * the {@code String} ones configured via {@code spring.kafka.consumer.*}.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> kafkaConsumerFactory,
            DefaultErrorHandler kafkaListenerErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, kafkaConsumerFactory);
        factory.setCommonErrorHandler(kafkaListenerErrorHandler);
        factory.getContainerProperties().setDeliveryAttemptHeader(true);
        return factory;
    }
}
