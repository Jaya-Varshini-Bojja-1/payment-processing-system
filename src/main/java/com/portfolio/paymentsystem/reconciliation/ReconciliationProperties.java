package com.portfolio.paymentsystem.reconciliation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Defaults live in code on purpose: no {@code application.yml} entry is
 * needed. Override with {@code app.reconciliation.max-findings} and
 * {@code app.reconciliation.stale-after} (ISO-8601 duration) if required.
 */
@ConfigurationProperties(prefix = "app.reconciliation")
public class ReconciliationProperties {

    /** Maximum findings returned per check; a {@code truncated} flag reports when more exist. */
    private int maxFindings = 50;

    /** Pending/publishing outbox rows and non-terminal payments older than this are reported as stale. */
    private Duration staleAfter = Duration.ofMinutes(15);

    public int getMaxFindings() {
        return maxFindings;
    }

    public void setMaxFindings(int maxFindings) {
        this.maxFindings = maxFindings;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }
}
