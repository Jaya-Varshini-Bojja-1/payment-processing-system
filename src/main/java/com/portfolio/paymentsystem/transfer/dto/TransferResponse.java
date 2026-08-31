package com.portfolio.paymentsystem.transfer.dto;

import java.time.Instant;
import java.util.UUID;

import com.portfolio.paymentsystem.common.dto.MoneyResponse;
import com.portfolio.paymentsystem.transfer.domain.Transfer;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A P2P transfer between two users' INR accounts")
public record TransferResponse(
        UUID id,
        UUID fromUserId,
        UUID toUserId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        MoneyResponse amount,
        String status,
        Instant createdAt
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getInitiatorUserId(),
                transfer.getDestinationUserId(),
                transfer.getSourceAccountId(),
                transfer.getDestinationAccountId(),
                new MoneyResponse(transfer.getAmountPaise(), transfer.getCurrency().name()),
                transfer.getStatus().name(),
                transfer.getCreatedAt());
    }
}
