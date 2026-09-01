package com.portfolio.paymentsystem.gateway;

/**
 * What a {@link SimulatedPaymentGateway} call decided, independent of any
 * particular attempt count or business type. The consumer that calls the
 * gateway (see {@code payment.event.PaymentProcessingConsumer}) maps each
 * outcome to a {@code PaymentTransaction} state transition; this enum
 * itself carries no retry-count or backoff knowledge — that is the
 * consumer/{@code PaymentOutcomeService}'s job, not the gateway's.
 */
public enum GatewayOutcome {
    /** Funds collected/paid out. Terminal — the caller may post the ledger. */
    SUCCESS,
    /** Transient failure (e.g. simulated timeout/overload). Safe to retry with a fresh attempt. */
    RETRYABLE_FAILURE,
    /** Deterministic decline (e.g. simulated "invalid destination"). Retrying would not help. */
    NON_RETRYABLE_FAILURE
}
