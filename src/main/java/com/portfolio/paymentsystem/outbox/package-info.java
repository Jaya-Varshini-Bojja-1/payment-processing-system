/**
 * Transactional outbox: durable events written in the same Postgres TX as domain rows.
 * Empty in M0; implemented in M4. HTTP never publishes to Kafka directly.
 */
package com.portfolio.paymentsystem.outbox;
