package com.portfolio.paymentsystem.payment.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

public class PaymentAccessDeniedException extends ApiException {

    public PaymentAccessDeniedException(UUID paymentId) {
        super(HttpStatus.FORBIDDEN, "PAYMENT_ACCESS_DENIED", "Payment '" + paymentId + "' does not belong to the caller");
    }
}
