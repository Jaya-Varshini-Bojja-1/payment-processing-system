package com.portfolio.paymentsystem.ledger.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.portfolio.paymentsystem.common.domain.Money;

class LedgerPostingRequestTest {

    @Test
    void rejectsReferenceTypeWithoutReferenceId() {
        assertThatThrownBy(() -> new LedgerPostingRequest(
                List.of(LedgerLineItem.debit(UUID.randomUUID(), Money.inr(100))),
                "TRANSFER", null, "desc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReferenceIdWithoutReferenceType() {
        assertThatThrownBy(() -> new LedgerPostingRequest(
                List.of(LedgerLineItem.debit(UUID.randomUUID(), Money.inr(100))),
                null, UUID.randomUUID(), "desc"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
