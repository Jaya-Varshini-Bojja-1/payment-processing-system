package com.portfolio.paymentsystem.wallet.dto;

import java.time.Instant;
import java.util.UUID;

import com.portfolio.paymentsystem.common.dto.MoneyResponse;
import com.portfolio.paymentsystem.ledger.domain.LedgerEntry;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One immutable ledger line affecting an account's balance")
public record LedgerEntryResponse(
        UUID id,
        UUID ledgerTransactionId,
        @Schema(example = "CREDIT") String entryType,
        MoneyResponse amount,
        Instant createdAt
) {
    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getLedgerTransactionId(),
                entry.getEntryType().name(),
                new MoneyResponse(entry.getAmountPaise(), entry.getCurrency().name()),
                entry.getCreatedAt());
    }
}
