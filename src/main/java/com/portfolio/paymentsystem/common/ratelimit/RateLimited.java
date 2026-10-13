package com.portfolio.paymentsystem.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as subject to the per-user Redis rate limiter.
 * {@link RateLimitInterceptor} looks for this annotation on the resolved
 * handler method; methods without it are never rate limited (no path
 * pattern matching — the annotation is the single source of truth for
 * which endpoints are money-moving POSTs).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimited {

    RateLimitScope value();
}
