package com.portfolio.paymentsystem.common.idempotency.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.idempotency")
public class IdempotencyProperties {

    /** How long a cached outcome survives in Redis. Purely a cache TTL — PostgreSQL keeps the record forever. */
    private Duration redisTtl = Duration.ofHours(24);

    public Duration getRedisTtl() {
        return redisTtl;
    }

    public void setRedisTtl(Duration redisTtl) {
        this.redisTtl = redisTtl;
    }
}
