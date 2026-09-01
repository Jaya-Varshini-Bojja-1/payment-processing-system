package com.portfolio.paymentsystem.wallet.domain;

/**
 * {@code USER} accounts are ordinary wallets and can never go negative
 * (see {@code ck_accounts_balance_non_negative} in the V6 migration).
 * {@code CLEARING} is the single system counterparty M5 posts against for
 * deposits/withdrawals (credit user + debit clearing, or vice versa) and is
 * explicitly allowed to run negative — net deposits over withdrawals is the
 * normal case, not an error.
 */
public enum AccountType {
    USER,
    CLEARING
}
