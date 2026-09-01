package com.portfolio.paymentsystem.payment.controller;

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
import com.portfolio.paymentsystem.identity.security.AuthenticatedUser;
import com.portfolio.paymentsystem.payment.domain.PaymentStatus;
import com.portfolio.paymentsystem.payment.domain.PaymentType;
import com.portfolio.paymentsystem.payment.dto.CreatePaymentRequest;
import com.portfolio.paymentsystem.payment.dto.PaymentResponse;
import com.portfolio.paymentsystem.payment.service.PaymentOutcome;
import com.portfolio.paymentsystem.payment.service.PaymentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Gateway-backed, asynchronous deposits and withdrawals (PaymentTransaction)")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/deposits")
    @Operation(
            summary = "Create a deposit",
            description = "Durably accepts a deposit request and returns immediately; the simulated gateway call and ledger "
                    + "post happen asynchronously. Requires a client-generated Idempotency-Key: a retry with the same key and "
                    + "an identical body returns the original result (200) instead of accepting a second deposit; reusing the "
                    + "key with a different body fails with 422.")
    @ApiResponse(responseCode = "202", description = "Deposit accepted; poll GET /payments/{id} for its outcome")
    @ApiResponse(responseCode = "200", description = "Replay of a previously accepted deposit with the same Idempotency-Key")
    @ApiResponse(responseCode = "400", description = "Validation failure or missing key")
    @ApiResponse(responseCode = "404", description = "Caller has no account")
    @ApiResponse(responseCode = "409", description = "Concurrent duplicate request in flight")
    @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with a different request body")
    public ResponseEntity<PaymentResponse> createDeposit(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Client-generated unique key for this payment request", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        return respond(paymentService.createDeposit(principal.userId(), idempotencyKey, request));
    }

    @PostMapping("/withdrawals")
    @Operation(
            summary = "Create a withdrawal",
            description = "Same acceptance/idempotency contract as deposits. Balance is checked at creation time as a fast "
                    + "rejection; the authoritative check happens again immediately before settlement, since the balance can "
                    + "move before the gateway confirms.")
    @ApiResponse(responseCode = "202", description = "Withdrawal accepted; poll GET /payments/{id} for its outcome")
    @ApiResponse(responseCode = "200", description = "Replay of a previously accepted withdrawal with the same Idempotency-Key")
    @ApiResponse(responseCode = "400", description = "Validation failure, missing key, or insufficient balance")
    @ApiResponse(responseCode = "404", description = "Caller has no account")
    @ApiResponse(responseCode = "409", description = "Concurrent duplicate request in flight")
    @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with a different request body")
    public ResponseEntity<PaymentResponse> createWithdrawal(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Client-generated unique key for this payment request", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        return respond(paymentService.createWithdrawal(principal.userId(), idempotencyKey, request));
    }

    private ResponseEntity<PaymentResponse> respond(PaymentOutcome outcome) {
        HttpStatus status = outcome.replay() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(outcome.response());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a payment by id", description = "Callers may only read their own payments.")
    @ApiResponse(responseCode = "200", description = "Payment found")
    @ApiResponse(responseCode = "403", description = "Payment does not belong to the caller")
    @ApiResponse(responseCode = "404", description = "Payment does not exist")
    public PaymentResponse getPayment(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser principal) {
        return paymentService.getPayment(id, principal.userId());
    }

    @GetMapping
    @Operation(summary = "List the caller's payments", description = "Newest first; optional type/status filters.")
    @ApiResponse(responseCode = "200", description = "Payment page")
    public PageResponse<PaymentResponse> listPayments(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) PaymentType type,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<PaymentResponse> payments = paymentService.listPayments(principal.userId(), type, status, pageable);
        return PageResponse.from(payments);
    }
}
