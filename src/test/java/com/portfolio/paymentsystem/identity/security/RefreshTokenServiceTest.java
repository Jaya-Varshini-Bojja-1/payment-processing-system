package com.portfolio.paymentsystem.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.portfolio.paymentsystem.identity.domain.RefreshToken;
import com.portfolio.paymentsystem.identity.domain.User;
import com.portfolio.paymentsystem.identity.exception.InvalidRefreshTokenException;
import com.portfolio.paymentsystem.identity.repository.RefreshTokenRepository;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService refreshTokenService;
    private User user;

    @BeforeEach
    void setUp() {
        RefreshTokenProperties properties = new RefreshTokenProperties();
        properties.setTtl(Duration.ofDays(7));
        refreshTokenService = new RefreshTokenService(refreshTokenRepository, properties);
        user = User.newUser("alice@example.com", "hashed-password");
    }

    @Test
    void issueForSavesAndReturnsRawToken() {
        RefreshTokenService.IssuedRefreshToken issued = refreshTokenService.issueFor(user);

        assertThat(issued.rawToken()).isNotBlank();
        assertThat(issued.expiresAt()).isAfter(Instant.now());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
        assertThat(captor.getValue().getTokenHash()).isNotEqualTo(issued.rawToken());
    }

    @Test
    void rotateRevokesOldTokenAndIssuesNew() {
        RefreshToken existing = RefreshToken.issue(user, "some-hash", Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(existing));

        RefreshTokenService.RotationResult result = refreshTokenService.rotate("raw-token-value");

        assertThat(result.userId()).isEqualTo(user.getId());
        assertThat(result.role()).isEqualTo(user.getRole());
        assertThat(result.rawToken()).isNotBlank();
        assertThat(existing.isRevoked()).isTrue();
        assertThat(existing.getReplacedByTokenId()).isNotNull();
        verify(refreshTokenRepository, times(2)).save(any());
    }

    @Test
    void rotateThrowsForUnknownToken() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.rotate("unknown"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void rotateThrowsForExpiredToken() {
        RefreshToken expired = RefreshToken.issue(user, "some-hash", Instant.now().minusSeconds(10));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> refreshTokenService.rotate("expired-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void rotateOfAlreadyRevokedTokenTriggersReuseDetectionAndThrows() {
        RefreshToken revoked = RefreshToken.issue(user, "some-hash", Instant.now().plusSeconds(3600));
        revoked.revoke();
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(revoked));
        when(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> refreshTokenService.rotate("replayed-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository).findAllByUserIdAndRevokedAtIsNull(user.getId());
    }

    @Test
    void revokeMarksMatchingTokenRevoked() {
        RefreshToken existing = RefreshToken.issue(user, "some-hash", Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(existing));

        refreshTokenService.revoke("raw-token-value");

        assertThat(existing.isRevoked()).isTrue();
    }

    @Test
    void revokeIsNoOpWhenTokenNotFound() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        refreshTokenService.revoke("unknown-token");

        verify(refreshTokenRepository, never()).save(any());
    }
}
