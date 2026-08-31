package com.portfolio.paymentsystem.transfer.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.portfolio.paymentsystem.common.exception.ApiException;

/**
 * The {@code toUserId} in a transfer request has no account yet. Kept
 * distinct from {@code AccountNotFoundException} (used for the caller's
 * own account) so the error message is unambiguous about which side of the
 * transfer failed to resolve.
 */
public class RecipientAccountNotFoundException extends ApiException {

    public RecipientAccountNotFoundException(UUID recipientUserId) {
        super(HttpStatus.NOT_FOUND, "RECIPIENT_ACCOUNT_NOT_FOUND",
                "Recipient user '" + recipientUserId + "' does not have an account");
    }
}
