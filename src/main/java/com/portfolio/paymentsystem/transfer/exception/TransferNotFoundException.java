package com.portfolio.paymentsystem.transfer.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

public class TransferNotFoundException extends ApiException {

    public TransferNotFoundException(UUID transferId) {
        super(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer '" + transferId + "' does not exist");
    }
}
