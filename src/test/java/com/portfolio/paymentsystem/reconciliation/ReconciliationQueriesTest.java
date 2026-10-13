package com.portfolio.paymentsystem.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Static guard: reconciliation SQL must only read. */
class ReconciliationQueriesTest {

    private static final Pattern WRITE_OR_DDL = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|DROP|ALTER|CREATE|GRANT|REVOKE|LOCK|COPY|CALL)\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    void everyStatementIsASelect() {
        assertThat(ReconciliationQueries.allSql()).isNotEmpty();
        for (String sql : ReconciliationQueries.allSql()) {
            assertThat(sql.stripLeading().toUpperCase()).as(sql).startsWith("SELECT");
        }
    }

    @Test
    void noStatementContainsAWriteOrDdlKeyword() {
        for (String sql : ReconciliationQueries.allSql()) {
            assertThat(WRITE_OR_DDL.matcher(sql).find()).as("write/DDL keyword in: " + sql).isFalse();
        }
    }

    @Test
    void noStatementTakesRowLocks() {
        for (String sql : ReconciliationQueries.allSql()) {
            assertThat(sql.toUpperCase()).as(sql).doesNotContain("FOR UPDATE").doesNotContain("FOR SHARE");
        }
    }
}
