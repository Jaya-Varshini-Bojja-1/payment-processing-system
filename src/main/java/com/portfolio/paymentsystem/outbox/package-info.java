/**
 * Transactional outbox: durable events written in the same Postgres TX as
 * domain rows (M4's first producer is {@code transfer.event.TransferEventPublisher}
 * writing {@code TransferCompleted} after a transfer commits). A scheduled
 * {@code OutboxPublisher} is the only thing that talks to Kafka; the HTTP
 * layer never publishes directly.
 */
package com.portfolio.paymentsystem.outbox;
