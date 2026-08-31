package com.portfolio.paymentsystem.transfer.exception;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * A user tried to transfer money to their own account. Checked before any
 * idempotency claim is made (see {@code TransferService}) — it is a
 * malformed request, not a business outcome worth caching/replaying.
 */
public class SelfTransferException extends ApiException {

    public SelfTransferException() {
        super(HttpStatus.BAD_REQUEST, "SELF_TRANSFER_NOT_ALLOWED", "Cannot transfer to your own account");
    }
}
