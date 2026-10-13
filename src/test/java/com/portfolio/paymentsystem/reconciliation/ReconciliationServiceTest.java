package com.portfolio.paymentsystem.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private ReconciliationQueries queries;

    private ReconciliationProperties properties;
    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        properties = new ReconciliationProperties();
        properties.setMaxFindings(3);
        properties.setStaleAfter(Duration.ofMinutes(10));
        service = new ReconciliationService(queries, properties);
    }

    private static Finding finding() {
        return new Finding("ACCOUNT", UUID.randomUUID(), "x", Map.of());
    }

    private static List<Finding> findings(int count) {
        return IntStream.range(0, count).mapToObj(i -> finding()).toList();
    }

    private void goodTransaction() {
        when(queries.transactionCharacteristics()).thenReturn(new TransactionCharacteristics("repeatable read", true));
    }

    private void allChecksEmpty() {
        when(queries.accountBalanceMismatches(anyInt())).thenReturn(List.of());
        when(queries.unbalancedLedgerTransactions(anyInt())).thenReturn(List.of());
        when(queries.systemTotalViolations()).thenReturn(List.of());
        when(queries.paymentLedgerMismatches(anyInt())).thenReturn(List.of());
        when(queries.transferLedgerMismatches(anyInt())).thenReturn(List.of());
        when(queries.failedOrStaleWork(anyInt(), anyLong())).thenReturn(List.of());
    }

    @Test
    void runsSixChecksWithErrorSeverityForR1ToR5AndWarningForR6() {
        goodTransaction();
        allChecksEmpty();

        ReconciliationReport report = service.reconcile();

        assertThat(report.checks()).extracting(CheckResult::checkId).containsExactly("R1", "R2", "R3", "R4", "R5", "R6");
        assertThat(report.checks()).extracting(CheckResult::severity).containsExactly(
                Severity.ERROR, Severity.ERROR, Severity.ERROR, Severity.ERROR, Severity.ERROR, Severity.WARNING);
        assertThat(report.clean()).isTrue();
        assertThat(report.generatedAt()).isNotNull();
    }

    @Test
    void fetchesOneMoreThanTheCapToDetectTruncationAndPassesStaleThresholdInSeconds() {
        goodTransaction();
        allChecksEmpty();

        service.reconcile();

        verify(queries).accountBalanceMismatches(4);
        verify(queries).unbalancedLedgerTransactions(4);
        verify(queries).paymentLedgerMismatches(4);
        verify(queries).transferLedgerMismatches(4);
        verify(queries).failedOrStaleWork(4, 600L);
    }

    @Test
    void trimsToTheCapAndFlagsTruncation() {
        goodTransaction();
        allChecksEmpty();
        when(queries.accountBalanceMismatches(anyInt())).thenReturn(findings(4)); // cap(3) + 1

        ReconciliationReport report = service.reconcile();

        CheckResult r1 = report.checks().get(0);
        assertThat(r1.truncated()).isTrue();
        assertThat(r1.findings()).hasSize(3);
        assertThat(r1.violationsReturned()).isEqualTo(3);
        assertThat(report.clean()).isFalse();
        assertThat(report.errorCount()).isEqualTo(3);
    }

    @Test
    void exactlyCapFindingsIsNotTruncated() {
        goodTransaction();
        allChecksEmpty();
        when(queries.unbalancedLedgerTransactions(anyInt())).thenReturn(findings(3));

        CheckResult r2 = service.reconcile().checks().get(1);

        assertThat(r2.truncated()).isFalse();
        assertThat(r2.findings()).hasSize(3);
    }

    @Test
    void warningsDoNotMakeTheReportDirty() {
        goodTransaction();
        allChecksEmpty();
        when(queries.failedOrStaleWork(anyInt(), anyLong())).thenReturn(findings(2));

        ReconciliationReport report = service.reconcile();

        assertThat(report.clean()).isTrue();
        assertThat(report.errorCount()).isZero();
        assertThat(report.warningCount()).isEqualTo(2);
    }

    @Test
    void errorFindingsMakeTheReportDirty() {
        goodTransaction();
        allChecksEmpty();
        when(queries.transferLedgerMismatches(anyInt())).thenReturn(findings(1));

        ReconciliationReport report = service.reconcile();

        assertThat(report.clean()).isFalse();
        assertThat(report.errorCount()).isEqualTo(1);
    }

    @Test
    void refusesToRunWhenNotInAReadOnlyRepeatableReadTransaction() {
        when(queries.transactionCharacteristics()).thenReturn(new TransactionCharacteristics("read committed", false));

        assertThatThrownBy(() -> service.reconcile())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only REPEATABLE READ");

        verify(queries, never()).accountBalanceMismatches(anyInt());
        verify(queries).transactionCharacteristics();
        verifyNoMoreInteractions(queries);
    }

    @Test
    void refusesToRunWhenReadOnlyButNotRepeatableRead() {
        when(queries.transactionCharacteristics()).thenReturn(new TransactionCharacteristics("read committed", true));

        assertThatThrownBy(() -> service.reconcile()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToRunWhenRepeatableReadButNotReadOnly() {
        when(queries.transactionCharacteristics()).thenReturn(new TransactionCharacteristics("repeatable read", false));

        assertThatThrownBy(() -> service.reconcile()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void capOfZeroOrLessIsTreatedAsOne() {
        properties.setMaxFindings(0);
        goodTransaction();
        allChecksEmpty();

        service.reconcile();

        verify(queries).accountBalanceMismatches(eq(2));
    }
}
