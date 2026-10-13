package com.portfolio.paymentsystem.transfer.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.portfolio.paymentsystem.common.dto.PageResponse;
import com.portfolio.paymentsystem.common.ratelimit.RateLimitScope;
import com.portfolio.paymentsystem.common.ratelimit.RateLimited;
import com.portfolio.paymentsystem.identity.security.AuthenticatedUser;
import com.portfolio.paymentsystem.transfer.domain.TransferStatus;
import com.portfolio.paymentsystem.transfer.dto.CreateTransferRequest;
import com.portfolio.paymentsystem.transfer.dto.TransferResponse;
import com.portfolio.paymentsystem.transfer.service.TransferOutcome;
import com.portfolio.paymentsystem.transfer.service.TransferService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Synchronous, ledger-backed P2P transfers between two users' INR accounts")
@SecurityRequirement(name = "bearerAuth")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @Operation(
            summary = "Create a P2P transfer",
            description = "Debits the caller's account and credits the recipient's, atomically, through the ledger. "
                    + "Requires a client-generated Idempotency-Key: a retry with the same key and an identical body "
                    + "returns the original result (200) instead of executing again; reusing the key with a "
                    + "different body fails with 422.")
    @ApiResponse(responseCode = "201", description = "Transfer created")
    @ApiResponse(responseCode = "200", description = "Replay of a previously completed transfer with the same Idempotency-Key")
    @ApiResponse(responseCode = "400", description = "Validation failure, self-transfer, missing key, or insufficient balance")
    @ApiResponse(responseCode = "404", description = "Caller or recipient has no account")
    @ApiResponse(responseCode = "409", description = "Concurrent duplicate request in flight, or transfer conflict after retries")
    @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with a different request body")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for transfer creation; retry after the given Retry-After seconds")
    @RateLimited(RateLimitScope.TRANSFER_CREATE)
    public ResponseEntity<TransferResponse> createTransfer(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Client-generated unique key for this transfer request", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        TransferOutcome outcome = transferService.createTransfer(principal.userId(), idempotencyKey, request);
        HttpStatus status = outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(outcome.response());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a transfer by id", description = "Callers may only read transfers where they are the sender or recipient.")
    @ApiResponse(responseCode = "200", description = "Transfer found")
    @ApiResponse(responseCode = "403", description = "Transfer does not involve the caller")
    @ApiResponse(responseCode = "404", description = "Transfer does not exist")
    public TransferResponse getTransfer(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser principal) {
        return transferService.getTransfer(id, principal.userId());
    }

    @GetMapping
    @Operation(summary = "List the caller's transfers", description = "Transfers where the caller is sender or recipient, newest first; optional status filter.")
    @ApiResponse(responseCode = "200", description = "Transfer page")
    public PageResponse<TransferResponse> listTransfers(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) TransferStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // Ordering is fixed in TransferRepository#findForUser's JPQL itself
        // (newest first), so the Pageable here deliberately carries no Sort.
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<TransferResponse> transfers = transferService.listTransfers(principal.userId(), status, pageable);
        return PageResponse.from(transfers);
    }
}
