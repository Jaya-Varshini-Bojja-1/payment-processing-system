/**
 * Transfer bounded context: internal P2P wallet-to-wallet movement (no
 * gateway). Implemented in M3: synchronous, ledger-backed, idempotent, with
 * transfer-level optimistic-lock retry — see {@code TransferService} and
 * {@code TransferExecutionService}.
 */
package com.portfolio.paymentsystem.transfer;
