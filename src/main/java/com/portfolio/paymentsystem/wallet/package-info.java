/**
 * Wallet bounded context: INR accounts and materialized balances.
 * Implemented in M2: {@code Account} (one per user, {@code @Version}-locked),
 * open/get/ledger-history APIs. Money movement itself lives in the ledger
 * module and later Transfer/Payment modules.
 */
package com.portfolio.paymentsystem.wallet;
