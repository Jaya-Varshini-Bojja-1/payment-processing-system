package com.portfolio.paymentsystem.payment.dto;

import java.time.Instant;
import java.util.UUID;

import com.portfolio.paymentsystem.common.dto.MoneyResponse;
import com.portfolio.paymentsystem.payment.domain.PaymentTransaction;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A gateway-backed deposit or withdrawal (PaymentTransaction)")
public record PaymentResponse(
        UUID id,
        UUID userId,
        UUID accountId,
        String type,
        MoneyResponse amount,
        String status,
        String gatewayReference,
        int attemptCount,
        String failureReason,
        Instant createdAt
) {
    public static PaymentResponse from(PaymentTransaction payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getUserId(),
                payment.getAccountId(),
                payment.getType().name(),
                new MoneyResponse(payment.getAmountPaise(), payment.getCurrency().name()),
                payment.getStatus().name(),
                payment.getGatewayReference(),
                payment.getAttemptCount(),
                payment.getFailureReason(),
                payment.getCreatedAt());
    }
}
