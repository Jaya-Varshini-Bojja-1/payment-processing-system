package com.portfolio.paymentsystem.reconciliation;

/**
 * How seriously a reconciliation finding should be taken.
 * <ul>
 *   <li>{@link #ERROR}: a financial-integrity invariant is violated (balances,
 *       ledger, or business rows disagree). Makes the report "dirty".</li>
 *   <li>{@link #WARNING}: an operational concern (stuck or failed async work)
 *       that may be transient. Reported, but never makes the report dirty.</li>
 * </ul>
 */
public enum Severity {
    ERROR,
    WARNING
}
