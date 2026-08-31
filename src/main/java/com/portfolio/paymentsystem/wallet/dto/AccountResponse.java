package com.portfolio.paymentsystem.wallet.dto;

import java.time.Instant;
import java.util.UUID;

import com.portfolio.paymentsystem.common.dto.MoneyResponse;
import com.portfolio.paymentsystem.wallet.domain.Account;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An account (wallet) and its current materialized balance")
public record AccountResponse(
        UUID id,
        MoneyResponse balance,
        Instant createdAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                new MoneyResponse(account.getBalancePaise(), account.getCurrency().name()),
                account.getCreatedAt());
    }
}
