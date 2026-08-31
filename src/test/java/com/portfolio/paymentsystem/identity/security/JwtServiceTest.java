package com.portfolio.paymentsystem.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.portfolio.paymentsystem.identity.domain.Role;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;

class JwtServiceTest {

    private static String randomBase64Secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static JwtProperties propertiesWith(Duration ttl) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(randomBase64Secret());
        properties.setAccessTokenTtl(ttl);
        properties.setIssuer("payment-processing-system-test");
        return properties;
    }

    @Test
    void generatesTokenWithExpectedClaims() {
        JwtService jwtService = new JwtService(propertiesWith(Duration.ofMinutes(15)));
        UUID userId = UUID.randomUUID();

        JwtService.GeneratedAccessToken generated = jwtService.generateAccessToken(userId, Role.USER);

        var claims = jwtService.parseAndValidate(generated.token());
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
        assertThat(claims.getId()).isEqualTo(generated.jti());
        assertThat(claims.getIssuer()).isEqualTo("payment-processing-system-test");
    }

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        JwtService issuer = new JwtService(propertiesWith(Duration.ofMinutes(15)));
        JwtService otherVerifier = new JwtService(propertiesWith(Duration.ofMinutes(15)));

        String token = issuer.generateAccessToken(UUID.randomUUID(), Role.USER).token();

        assertThatThrownBy(() -> otherVerifier.parseAndValidate(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() throws InterruptedException {
        JwtService jwtService = new JwtService(propertiesWith(Duration.ofMillis(1)));
        String token = jwtService.generateAccessToken(UUID.randomUUID(), Role.USER).token();

        Thread.sleep(20);

        assertThatThrownBy(() -> jwtService.parseAndValidate(token)).isInstanceOf(ExpiredJwtException.class);
    }
}
