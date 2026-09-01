package com.portfolio.paymentsystem.payment.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * Raised twice in a withdrawal's life, from two different callers, for two
 * different reasons:
 * <ul>
 *   <li>{@code PaymentExecutionService} at creation time — a fast,
 *       user-facing rejection before any outbox/Kafka machinery runs.</li>
 *   <li>{@code PaymentOutcomeService} immediately before the settlement
 *       ledger post — the authoritative re-check, since the balance can
 *       have moved (another withdrawal, a transfer) in the time between
 *       request and gateway success. See PROJECT_PLAN.md section 8C.</li>
 * </ul>
 */
public class InsufficientBalanceException extends ApiException {

    public InsufficientBalanceException(UUID accountId, long availablePaise, long requestedPaise) {
        super(HttpStatus.BAD_REQUEST, "INSUFFICIENT_BALANCE",
                "Account '" + accountId + "' has balance " + availablePaise
                        + " paise, which is less than the requested " + requestedPaise + " paise");
    }
}
