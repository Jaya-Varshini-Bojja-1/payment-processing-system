package com.portfolio.paymentsystem.transfer.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * A caller tried to read a transfer they are neither the sender nor the
 * recipient of. Mirrors {@code AccountAccessDeniedException}'s reasoning:
 * {@code 403} is clearer than {@code 404} for a portfolio API.
 */
public class TransferAccessDeniedException extends ApiException {

    public TransferAccessDeniedException(UUID transferId) {
        super(HttpStatus.FORBIDDEN, "TRANSFER_ACCESS_DENIED", "Transfer '" + transferId + "' does not belong to the caller");
    }
}
