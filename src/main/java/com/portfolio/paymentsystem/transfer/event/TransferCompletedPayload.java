package com.portfolio.paymentsystem.transfer.event;

import java.util.UUID;

/**
 * The minimal domain payload for a completed transfer — enough for a
 * consumer to know what happened without re-fetching the row, but not a
 * full snapshot of every {@code Transfer} column. Deliberately mirrors the
 * "minimal payload" guidance in the project plan for M5's payment events:
 * this is a notification, not an event-sourcing record.
 */
public record TransferCompletedPayload(
        UUID transferId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amountPaise,
        String currency
) {
}
