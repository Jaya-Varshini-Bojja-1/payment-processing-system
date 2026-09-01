/**
 * Payment bounded context: gateway-backed deposits and withdrawals
 * ({@link com.portfolio.paymentsystem.payment.domain.PaymentTransaction}),
 * distinct from {@code transfer}'s internal P2P {@code Transfer}. Async via
 * the transactional outbox (see {@code outbox}) and the simulated gateway
 * (see {@code gateway}) — never called synchronously from the API layer.
 */
package com.portfolio.paymentsystem.payment;
