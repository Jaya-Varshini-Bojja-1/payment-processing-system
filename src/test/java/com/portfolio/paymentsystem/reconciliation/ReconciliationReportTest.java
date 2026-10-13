package com.portfolio.paymentsystem.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReconciliationReportTest {

    private static Finding finding() {
        return new Finding("ACCOUNT", UUID.randomUUID(), "x", Map.of());
    }

    private static CheckResult check(Severity severity, int findings) {
        List<Finding> list = java.util.stream.IntStream.range(0, findings).mapToObj(i -> finding()).toList();
        return new CheckResult("RX", "name", severity, list.size(), false, list);
    }

    @Test
    void noFindingsIsClean() {
        ReconciliationReport report = ReconciliationReport.of(Instant.now(),
                List.of(check(Severity.ERROR, 0), check(Severity.WARNING, 0)));

        assertThat(report.clean()).isTrue();
        assertThat(report.errorCount()).isZero();
        assertThat(report.warningCount()).isZero();
    }

    @Test
    void warningsAloneDoNotMakeTheReportDirty() {
        ReconciliationReport report = ReconciliationReport.of(Instant.now(),
                List.of(check(Severity.ERROR, 0), check(Severity.WARNING, 3)));

        assertThat(report.clean()).isTrue();
        assertThat(report.errorCount()).isZero();
        assertThat(report.warningCount()).isEqualTo(3);
    }

    @Test
    void anyErrorFindingMakesTheReportDirty() {
        ReconciliationReport report = ReconciliationReport.of(Instant.now(),
                List.of(check(Severity.ERROR, 2), check(Severity.WARNING, 1)));

        assertThat(report.clean()).isFalse();
        assertThat(report.errorCount()).isEqualTo(2);
        assertThat(report.warningCount()).isEqualTo(1);
    }

    @Test
    void generatedAtAndChecksArePreserved() {
        Instant now = Instant.parse("2026-10-13T08:00:00Z");
        List<CheckResult> checks = List.of(check(Severity.ERROR, 1));

        ReconciliationReport report = ReconciliationReport.of(now, checks);

        assertThat(report.generatedAt()).isEqualTo(now);
        assertThat(report.checks()).containsExactlyElementsOf(checks);
    }
}
