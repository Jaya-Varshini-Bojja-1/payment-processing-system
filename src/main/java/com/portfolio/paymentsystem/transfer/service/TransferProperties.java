package com.portfolio.paymentsystem.transfer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.transfer")
public class TransferProperties {

    /**
     * Bounded retry count for optimistic-lock conflicts at the transfer
     * boundary (see {@code TransferService}). Each attempt re-reads both
     * accounts and re-validates sufficient balance from scratch — this is
     * not "retry the same delta," it is "re-run the whole decision."
     */
    private int maxOptimisticLockAttempts = 3;

    public int getMaxOptimisticLockAttempts() {
        return maxOptimisticLockAttempts;
    }

    public void setMaxOptimisticLockAttempts(int maxOptimisticLockAttempts) {
        this.maxOptimisticLockAttempts = maxOptimisticLockAttempts;
    }
}
