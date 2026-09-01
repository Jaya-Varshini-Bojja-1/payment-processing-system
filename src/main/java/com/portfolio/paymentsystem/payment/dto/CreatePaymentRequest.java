package com.portfolio.paymentsystem.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

@Schema(description = "A deposit or withdrawal request. Currency is implicitly INR (V1 supports no other currency). "
        + "Trailing digits of amountPaise select the simulated gateway's demo scenario -- see GatewayProperties.")
public record CreatePaymentRequest(

        @Schema(example = "50000", description = "Amount in paise (e.g. 50000 = INR 500.00)")
        @Positive(message = "amountPaise must be positive")
        long amountPaise
) {
}
