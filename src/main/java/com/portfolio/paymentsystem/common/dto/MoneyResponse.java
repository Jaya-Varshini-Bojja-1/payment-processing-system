package com.portfolio.paymentsystem.common.dto;

import com.portfolio.paymentsystem.common.domain.Money;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An amount of money as integer paise plus its currency; never a decimal/float.")
public record MoneyResponse(
        @Schema(example = "100000") long amountPaise,
        @Schema(example = "INR") String currency
) {
    public static MoneyResponse from(Money money) {
        return new MoneyResponse(money.amountPaise(), money.currency().name());
    }
}
