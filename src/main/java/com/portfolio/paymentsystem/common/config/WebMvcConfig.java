package com.portfolio.paymentsystem.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.portfolio.paymentsystem.common.ratelimit.RateLimitInterceptor;

/**
 * M6: registers the {@code @RateLimited} interceptor. No path patterns are
 * needed here — every request still passes through this interceptor, but
 * it is a no-op for any handler method that isn't annotated (see
 * {@link RateLimitInterceptor}).
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor);
    }
}
