package com.portfolio.paymentsystem.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void inrFactoryCreatesInrMoney() {
        Money money = Money.inr(150_000);

        assertThat(money.amountPaise()).isEqualTo(150_000);
        assertThat(money.currency()).isEqualTo(Currency.INR);
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> Money.inr(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsZeroAmount() {
        // Zero is a structurally valid Money value (e.g. a freshly opened
        // account's balance); LedgerService separately rejects zero-amount
        // *ledger lines*, since posting "no money" is meaningless.
        assertThat(Money.inr(0).amountPaise()).isZero();
    }
}
