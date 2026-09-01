package com.portfolio.paymentsystem.payment.service;

import com.portfolio.paymentsystem.payment.dto.PaymentResponse;

/**
 * {@code replay = true} means the caller ({@code PaymentController}) should
 * respond {@code 200 OK} — an idempotent replay, nothing new was created.
 * {@code replay = false} means this call just durably accepted a new
 * payment and the controller should respond {@code 202 Accepted} (not
 * {@code 201}: unlike a synchronous {@code Transfer}, the money movement
 * has not happened yet).
 */
public record PaymentOutcome(boolean replay, PaymentResponse response) {

    public static PaymentOutcome fresh(PaymentResponse response) {
        return new PaymentOutcome(false, response);
    }

    public static PaymentOutcome replay(PaymentResponse response) {
        return new PaymentOutcome(true, response);
    }
}
