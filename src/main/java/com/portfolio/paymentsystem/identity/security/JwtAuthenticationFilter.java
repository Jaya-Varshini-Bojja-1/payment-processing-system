package com.portfolio.paymentsystem.identity.security;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.portfolio.paymentsystem.identity.domain.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Validates the {@code Authorization: Bearer <token>} header on every
 * request. On success, populates the {@link SecurityContextHolder} with an
 * {@link AuthenticatedUser} principal; otherwise leaves the context empty so
 * Spring Security's access-control rules (and {@link JwtAuthenticationEntryPoint})
 * handle the rejection for endpoints that require authentication.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenDenylistService tokenDenylistService;

    public JwtAuthenticationFilter(JwtService jwtService, TokenDenylistService tokenDenylistService) {
        this.jwtService = jwtService;
        this.tokenDenylistService = tokenDenylistService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);
        if (token != null) {
            try {
                Claims claims = jwtService.parseAndValidate(token);
                if (!tokenDenylistService.isDenylisted(claims.getId())) {
                    authenticate(claims, request);
                } else {
                    log.debug("Rejected denylisted access token jti={}", claims.getId());
                }
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("Rejected invalid access token: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(Claims claims, HttpServletRequest request) {
        UUID userId = UUID.fromString(claims.getSubject());
        Role role = Role.valueOf(claims.get("role", String.class));
        AuthenticatedUser principal = new AuthenticatedUser(userId, role);

        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
