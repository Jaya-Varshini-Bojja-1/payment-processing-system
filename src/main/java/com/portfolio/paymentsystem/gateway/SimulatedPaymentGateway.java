package com.portfolio.paymentsystem.gateway;

/**
 * The port a real integration would sit behind (a real Stripe/UPI PSP
 * adapter would implement this same interface). {@code payment.event}'s
 * consumer calls {@link #process} <b>outside</b> any Postgres transaction —
 * this is the "external I/O" step in M5's async flow, and must stay that
 * way even once a real adapter replaces {@link InMemorySimulatedPaymentGateway}.
 */
public interface SimulatedPaymentGateway {

    GatewayResult process(GatewayRequest request);
}
