package com.portfolio.paymentsystem.wallet.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.portfolio.paymentsystem.common.dto.PageResponse;
import com.portfolio.paymentsystem.identity.security.AuthenticatedUser;
import com.portfolio.paymentsystem.ledger.domain.LedgerEntry;
import com.portfolio.paymentsystem.wallet.domain.Account;
import com.portfolio.paymentsystem.wallet.dto.AccountResponse;
import com.portfolio.paymentsystem.wallet.dto.LedgerEntryResponse;
import com.portfolio.paymentsystem.wallet.service.AccountService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Wallet creation, balance, and ledger history")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Open an INR wallet", description = "Creates the caller's account with a zero balance. V1 allows exactly one account per user.")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "409", description = "Caller already has an account")
    public ResponseEntity<AccountResponse> openAccount(@AuthenticationPrincipal AuthenticatedUser principal) {
        Account account = accountService.openAccount(principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    @GetMapping("/me")
    @Operation(summary = "Get the caller's account", description = "Returns the caller's account id, currency, and current materialized balance.")
    @ApiResponse(responseCode = "200", description = "Account found")
    @ApiResponse(responseCode = "404", description = "Caller has no account yet")
    public AccountResponse getMyAccount(@AuthenticationPrincipal AuthenticatedUser principal) {
        return AccountResponse.from(accountService.getMyAccount(principal.userId()));
    }

    @GetMapping("/{id}/ledger")
    @Operation(summary = "Get an account's ledger history", description = "Append-only, newest-first. Callers may only read their own account's ledger.")
    @ApiResponse(responseCode = "200", description = "Ledger page")
    @ApiResponse(responseCode = "403", description = "Account does not belong to the caller")
    @ApiResponse(responseCode = "404", description = "Account does not exist")
    public PageResponse<LedgerEntryResponse> getLedgerHistory(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<LedgerEntry> entries = accountService.getLedgerHistory(id, principal.userId(), pageable);
        return PageResponse.from(entries.map(LedgerEntryResponse::from));
    }
}
