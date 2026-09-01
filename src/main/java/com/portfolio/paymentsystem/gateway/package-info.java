/**
 * Simulated payment gateway port ({@link com.portfolio.paymentsystem.gateway.SimulatedPaymentGateway})
 * and its deterministic, amount-bucketed adapter
 * ({@link com.portfolio.paymentsystem.gateway.InMemorySimulatedPaymentGateway}).
 * Has no dependency on {@code payment} — it moves an amount and reports an
 * outcome, nothing more.
 */
package com.portfolio.paymentsystem.gateway;
