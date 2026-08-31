package com.portfolio.paymentsystem.transfer.service;

import com.portfolio.paymentsystem.transfer.dto.TransferResponse;

/**
 * {@code replay = true} means the caller ({@code TransferController}) should
 * respond {@code 200 OK} — nothing new happened, this is a cached success
 * being returned again. {@code replay = false} means this attempt truly
 * just created the transfer and the controller should respond
 * {@code 201 Created}.
 */
public record TransferOutcome(boolean replay, TransferResponse response) {

    public static TransferOutcome fresh(TransferResponse response) {
        return new TransferOutcome(false, response);
    }

    public static TransferOutcome replay(TransferResponse response) {
        return new TransferOutcome(true, response);
    }
}
