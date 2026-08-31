package com.portfolio.paymentsystem.transfer.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "A P2P transfer request. Currency is implicitly INR (V1 supports no other currency).")
public record CreateTransferRequest(

        @Schema(description = "The recipient's user id. V1 allows exactly one account per user, so the destination account is resolved from this.")
        @NotNull(message = "toUserId is required")
        UUID toUserId,

        @Schema(example = "50000", description = "Amount in paise (e.g. 50000 = INR 500.00)")
        @Positive(message = "amountPaise must be positive")
        long amountPaise
) {
}
