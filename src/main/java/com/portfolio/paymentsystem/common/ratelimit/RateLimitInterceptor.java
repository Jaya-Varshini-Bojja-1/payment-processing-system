package com.portfolio.paymentsystem.common.ratelimit;

import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.portfolio.paymentsystem.identity.security.AuthenticatedUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runs after Spring Security's filter chain (so the authenticated
 * principal, if any, is already on {@link SecurityContextHolder}) but
 * before the handler method executes — the earliest point that both (a)
 * knows the caller's {@code userId} and (b) can still short-circuit before
 * any idempotency/business-logic work runs.
 * <p>
 * Only methods annotated {@link RateLimited} are checked; every other
 * handler passes through untouched.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiterService rateLimiterService;

    public RateLimitInterceptor(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimited annotation = handlerMethod.getMethodAnnotation(RateLimited.class);
        if (annotation == null) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (!(principal instanceof AuthenticatedUser authenticatedUser)) {
            // No authenticated principal to key the counter on. Every @RateLimited
            // endpoint also requires authentication (enforced by SecurityConfig), so
            // this should be unreachable in practice; fail safe by allowing rather
            // than risking an NPE ahead of Spring Security's own 401 handling.
            return true;
        }

        RateLimitResult result = rateLimiterService.tryConsume(annotation.value(), authenticatedUser.userId());
        if (!result.allowed()) {
            throw new RateLimitExceededException(annotation.value(), result.retryAfterSeconds());
        }
        return true;
    }
}
