package com.portfolio.paymentsystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} backs the M4 {@code OutboxPublisher}'s
 * {@code @Scheduled} poll loop — the only scheduled job in this
 * application. It runs unconditionally; {@code OutboxPublisher} itself is
 * the piece that is conditionally registered (see
 * {@code app.outbox.publisher-enabled}) so the general test classpath
 * never needs a running Kafka broker.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PaymentSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentSystemApplication.class, args);
    }
}
