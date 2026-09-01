package com.portfolio.paymentsystem.gateway;

import java.util.UUID;

/**
 * Deliberately has no notion of "deposit" vs "withdrawal" — a payment
 * gateway moves an amount and reports what happened; which ledger accounts
 * that maps to is a decision the payment module makes on the way back, not
 * something the gateway itself needs to know. Keeping this port free of any
 * {@code payment.domain} type is what lets {@code gateway} have zero
 * dependency on {@code payment} despite {@code payment} depending on it.
 */
public record GatewayRequest(UUID paymentTransactionId, long amountPaise, int attemptNumber) {
}
