package com.portfolio.paymentsystem.transfer.exception;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * The bounded optimistic-lock retry loop in {@code TransferService} was
 * exhausted: every attempt lost the version race on one of the two
 * accounts. Extremely rare in practice (it requires sustained contention
 * across every retry), and deliberately not retried further — see
 * {@code TransferService}'s class-level javadoc. The client may retry the
 * whole request; {@code TransferService} releases the idempotency claim
 * before throwing this, so the same {@code Idempotency-Key} remains usable.
 */
public class TransferConflictException extends ApiException {

    public TransferConflictException(int attempts) {
        super(HttpStatus.CONFLICT, "TRANSFER_CONFLICT",
                "Transfer could not be completed after " + attempts + " attempts due to concurrent updates; please retry");
    }
}
