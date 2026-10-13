package com.portfolio.paymentsystem.outbox.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Topic names live here (not hardcoded as string literals at each call
 * site, and not derived from event type by the publisher) so the module
 * that owns an event decides its destination once, in config, the same way
 * connection details are env-based elsewhere in this project.
 */
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxProperties {

    private String transferCompletedTopic = "transfers.completed";
    private String transferDlqTopic = "transfers.dlq";

    /**
     * M5: one processing topic for both deposit and withdrawal
     * {@code PaymentTransaction}s (see PROJECT_PLAN.md section 11 — "not a
     * complex delayed-topic topology," a single topic keyed by
     * {@code paymentTransactionId} is enough).
     */
    private String paymentProcessingTopic = "payments.process";
    private String paymentDlqTopic = "payments.dlq";

    /**
     * Registers the scheduled publisher bean at all. Overridden to
     * {@code false} for the general test classpath (see
     * {@code src/test/resources/application.yml}) so ordinary Postgres/Redis
     * integration tests never attempt a background Kafka connection; the
     * Kafka-specific tests in M4 turn it back on explicitly.
     */
    private boolean publisherEnabled = true;

    /**
     * Gates only the {@code @Scheduled} poll loop itself, independent of
     * {@link #publisherEnabled} (which gates whether the bean exists at
     * all). A test that wants to call {@code publishDueEvents()} directly
     * and deterministically — without an ambient background tick racing
     * its own call for the same rows — sets this {@code false} while
     * leaving {@link #publisherEnabled} {@code true} so the bean is still
     * there to autowire.
     */
    private boolean schedulerEnabled = true;

    /** How many due rows one publisher tick claims and sends. */
    private int publishBatchSize = 20;

    /** Delay between publisher ticks. */
    private long pollIntervalMs = 2000;

    /** Send attempts (including the first) before a row is marked FAILED and pushed to its DLQ topic. */
    private int maxAttempts = 5;

    /** Backoff before the first retry; doubled (see {@link #backoffMultiplier}) on each subsequent one. */
    private Duration initialBackoff = Duration.ofSeconds(1);

    private double backoffMultiplier = 2.0;

    /** How long the publisher blocks waiting for Kafka to acknowledge one send. */
    private Duration publishTimeout = Duration.ofSeconds(5);

    /**
     * A row stuck in PUBLISHING longer than this is assumed to belong to a
     * publisher instance that died mid-send, and is reclaimed back to
     * PENDING without counting as a failed attempt.
     */
    private Duration stalePublishingTimeout = Duration.ofMinutes(1);

    /**
     * Consumer-side retries (see {@code KafkaConsumerConfig}), independent
     * of the publisher's own attempt/backoff settings above — a listener
     * failure (e.g. a transient DB error while writing to the inbox) is a
     * different failure stage from a publish failure, even though both can
     * end up on the same DLQ topic (see {@code DlqFailureStage}).
     */
    private int consumerMaxAttempts = 3;

    private Duration consumerRetryBackoff = Duration.ofMillis(500);

    public String getTransferCompletedTopic() {
        return transferCompletedTopic;
    }

    public void setTransferCompletedTopic(String transferCompletedTopic) {
        this.transferCompletedTopic = transferCompletedTopic;
    }

    public String getTransferDlqTopic() {
        return transferDlqTopic;
    }

    public void setTransferDlqTopic(String transferDlqTopic) {
        this.transferDlqTopic = transferDlqTopic;
    }

    public String getPaymentProcessingTopic() {
        return paymentProcessingTopic;
    }

    public void setPaymentProcessingTopic(String paymentProcessingTopic) {
        this.paymentProcessingTopic = paymentProcessingTopic;
    }

    public String getPaymentDlqTopic() {
        return paymentDlqTopic;
    }

    public void setPaymentDlqTopic(String paymentDlqTopic) {
        this.paymentDlqTopic = paymentDlqTopic;
    }

    public boolean isPublisherEnabled() {
        return publisherEnabled;
    }

    public void setPublisherEnabled(boolean publisherEnabled) {
        this.publisherEnabled = publisherEnabled;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public int getPublishBatchSize() {
        return publishBatchSize;
    }

    public void setPublishBatchSize(int publishBatchSize) {
        this.publishBatchSize = publishBatchSize;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public Duration getPublishTimeout() {
        return publishTimeout;
    }

    public void setPublishTimeout(Duration publishTimeout) {
        this.publishTimeout = publishTimeout;
    }

    public Duration getStalePublishingTimeout() {
        return stalePublishingTimeout;
    }

    public void setStalePublishingTimeout(Duration stalePublishingTimeout) {
        this.stalePublishingTimeout = stalePublishingTimeout;
    }

    public int getConsumerMaxAttempts() {
        return consumerMaxAttempts;
    }

    public void setConsumerMaxAttempts(int consumerMaxAttempts) {
        this.consumerMaxAttempts = consumerMaxAttempts;
    }

    public Duration getConsumerRetryBackoff() {
        return consumerRetryBackoff;
    }

    public void setConsumerRetryBackoff(Duration consumerRetryBackoff) {
        this.consumerRetryBackoff = consumerRetryBackoff;
    }
}
