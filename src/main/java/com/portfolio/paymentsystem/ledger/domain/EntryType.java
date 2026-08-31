package com.portfolio.paymentsystem.ledger.domain;

/**
 * From the account HOLDER's point of view (not strict T-account
 * accounting, where meaning depends on asset vs liability classification):
 * <ul>
 *   <li>{@code CREDIT} — money in; increases the account's balance.</li>
 *   <li>{@code DEBIT} — money out; decreases the account's balance.</li>
 * </ul>
 * A balanced {@link LedgerTransaction} has its entries' CREDIT total equal
 * its DEBIT total.
 */
public enum EntryType {
    DEBIT,
    CREDIT
}
