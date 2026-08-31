package com.portfolio.paymentsystem.common.domain;

/**
 * An amount of money as integer minor units ("paise" for INR) plus its
 * currency. Never a {@code float}/{@code double} — binary floating point
 * cannot represent decimal fractions like 0.10 exactly, which is
 * unacceptable for money. Never a bare {@code long}, either: a raw long
 * amount says nothing about currency and invites accidental unit mixing
 * (e.g. rupees vs paise) at call sites.
 * <p>
 * Deliberately minimal for M2 — just enough for account balances and
 * ledger entries. Arithmetic helpers can be added when a real use case
 * (e.g. Transfer) needs them.
 */
public record Money(long amountPaise, Currency currency) {

    public Money {
        if (amountPaise < 0) {
            throw new IllegalArgumentException("amountPaise must not be negative: " + amountPaise);
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency must not be null");
        }
    }

    public static Money inr(long amountPaise) {
        return new Money(amountPaise, Currency.INR);
    }
}
