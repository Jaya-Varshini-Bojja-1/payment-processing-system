/**
 * Ledger bounded context: double-entry journal posts (source of truth for
 * money). Implemented in M2: {@code LedgerService.postTransaction} is the
 * only path that changes a balance, always posting balanced, immutable
 * {@code LedgerEntry} rows and updating {@code Account.balancePaise} in one
 * database transaction. Later milestones (Transfer, PaymentTransaction)
 * call this service rather than reimplementing money movement.
 */
package com.portfolio.paymentsystem.ledger;
