package com.portfolio.paymentsystem.gateway;

public record GatewayResult(GatewayOutcome outcome, String gatewayReference, String failureReason) {

    public static GatewayResult success(String gatewayReference) {
        return new GatewayResult(GatewayOutcome.SUCCESS, gatewayReference, null);
    }

    public static GatewayResult retryableFailure(String failureReason) {
        return new GatewayResult(GatewayOutcome.RETRYABLE_FAILURE, null, failureReason);
    }

    public static GatewayResult nonRetryableFailure(String failureReason) {
        return new GatewayResult(GatewayOutcome.NON_RETRYABLE_FAILURE, null, failureReason);
    }
}
