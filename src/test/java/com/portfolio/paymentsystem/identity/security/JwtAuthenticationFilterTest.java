package com.portfolio.paymentsystem.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.portfolio.paymentsystem.identity.domain.Role;

import io.jsonwebtoken.Claims;

import jakarta.servlet.FilterChain;

/**
 * Defense-in-depth proof that {@link TokenDenylistService}'s M6 fail-CLOSED
 * policy actually blocks the request end to end, not just that the service
 * returns the right boolean in isolation (that half is
 * {@code TokenDenylistServiceTest}'s job). Whether {@code isDenylisted}
 * returns {@code true} because the token is genuinely denylisted, or
 * because Redis is unreachable and the service failed closed, this filter
 * must behave identically: never authenticate.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;
    @Mock
    private TokenDenylistService tokenDenylistService;
    @Mock
    private FilterChain filterChain;
    @Mock
    private Claims claims;

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtService, tokenDenylistService);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doesNotAuthenticateWhenTheTokenIsDenylisted() throws Exception {
        request.addHeader("Authorization", "Bearer some-token");
        String jti = "jti-1";
        when(jwtService.parseAndValidate("some-token")).thenReturn(claims);
        when(claims.getId()).thenReturn(jti);
        when(tokenDenylistService.isDenylisted(jti)).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    /**
     * Same assertion as above, framed explicitly as the Redis-outage case:
     * {@code isDenylisted} returning {@code true} because it failed CLOSED
     * (not because the token was actually revoked) must still result in
     * "not authenticated" here — this filter has no way to distinguish the
     * two, by design.
     */
    @Test
    void doesNotAuthenticateWhenDenylistCheckFailsClosedDuringARedisOutage() throws Exception {
        request.addHeader("Authorization", "Bearer some-token");
        String jti = "jti-2";
        when(jwtService.parseAndValidate("some-token")).thenReturn(claims);
        when(claims.getId()).thenReturn(jti);
        when(tokenDenylistService.isDenylisted(jti)).thenReturn(true); // TokenDenylistService's own fail-closed contract

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void authenticatesWhenTheTokenIsValidAndNotDenylisted() throws Exception {
        request.addHeader("Authorization", "Bearer some-token");
        UUID userId = UUID.randomUUID();
        String jti = "jti-3";
        when(jwtService.parseAndValidate("some-token")).thenReturn(claims);
        when(claims.getId()).thenReturn(jti);
        when(claims.getSubject()).thenReturn(userId.toString());
        when(claims.get("role", String.class)).thenReturn(Role.USER.name());
        when(tokenDenylistService.isDenylisted(jti)).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.role()).isEqualTo(Role.USER);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doesNotAuthenticateWhenNoAuthorizationHeaderIsPresent() throws Exception {
        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }
}
