package com.portfolio.paymentsystem.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import com.portfolio.paymentsystem.identity.domain.Role;
import com.portfolio.paymentsystem.identity.security.AuthenticatedUser;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    private RateLimiterService rateLimiterService;

    private RateLimitInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor(rateLimiterService);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void passesThroughHandlersWithoutTheAnnotation() throws Exception {
        authenticateAs(UUID.randomUUID());

        boolean result = interceptor.preHandle(request, response, plainHandlerMethod());

        assertThat(result).isTrue();
        verifyNoInteractions(rateLimiterService);
    }

    @Test
    void passesThroughNonHandlerMethodTargets() {
        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        verifyNoInteractions(rateLimiterService);
    }

    @Test
    void allowsWhenUnderTheLimit() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(userId);
        when(rateLimiterService.tryConsume(eq(RateLimitScope.TRANSFER_CREATE), eq(userId)))
                .thenReturn(RateLimitResult.allow());

        boolean result = interceptor.preHandle(request, response, rateLimitedHandlerMethod());

        assertThat(result).isTrue();
    }

    @Test
    void throwsWhenOverTheLimit() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(userId);
        when(rateLimiterService.tryConsume(eq(RateLimitScope.TRANSFER_CREATE), eq(userId)))
                .thenReturn(RateLimitResult.deny(30));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, rateLimitedHandlerMethod()))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> {
                    RateLimitExceededException rateLimitEx = (RateLimitExceededException) ex;
                    assertThat(rateLimitEx.getRetryAfterSeconds()).isEqualTo(30);
                    assertThat(rateLimitEx.getScope()).isEqualTo(RateLimitScope.TRANSFER_CREATE);
                });
    }

    @Test
    void allowsWhenThereIsNoAuthenticatedPrincipal() throws Exception {
        SecurityContextHolder.clearContext();

        boolean result = interceptor.preHandle(request, response, rateLimitedHandlerMethod());

        assertThat(result).isTrue();
        verifyNoInteractions(rateLimiterService);
    }

    private void authenticateAs(UUID userId) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, Role.USER);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private HandlerMethod rateLimitedHandlerMethod() throws NoSuchMethodException {
        return new HandlerMethod(new AnnotatedTarget(), AnnotatedTarget.class.getMethod("rateLimited"));
    }

    private HandlerMethod plainHandlerMethod() throws NoSuchMethodException {
        return new HandlerMethod(new AnnotatedTarget(), AnnotatedTarget.class.getMethod("plain"));
    }

    /** A minimal stand-in controller purely to give {@link HandlerMethod} a real annotated method to introspect. */
    static class AnnotatedTarget {

        @RateLimited(RateLimitScope.TRANSFER_CREATE)
        public void rateLimited() {
        }

        public void plain() {
        }
    }
}
