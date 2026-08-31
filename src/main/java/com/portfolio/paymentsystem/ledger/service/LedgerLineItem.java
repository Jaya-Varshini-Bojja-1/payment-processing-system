package com.portfolio.paymentsystem.ledger.service;

import java.util.UUID;

import com.portfolio.paymentsystem.common.domain.Money;
import com.portfolio.paymentsystem.ledger.domain.EntryType;

/**
 * One line of a proposed journal post: "move this much money, this
 * direction, on this account." A {@link LedgerPostingRequest} bundles two
 * or more of these that must balance.
 */
public record LedgerLineItem(UUID accountId, EntryType entryType, Money amount) {

    public static LedgerLineItem debit(UUID accountId, Money amount) {
        return new LedgerLineItem(accountId, EntryType.DEBIT, amount);
    }

    public static LedgerLineItem credit(UUID accountId, Money amount) {
        return new LedgerLineItem(accountId, EntryType.CREDIT, amount);
    }
}
