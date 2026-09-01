package com.portfolio.paymentsystem.payment.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

public class PaymentNotFoundException extends ApiException {

    public PaymentNotFoundException(UUID paymentId) {
        super(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment '" + paymentId + "' does not exist");
    }
}
