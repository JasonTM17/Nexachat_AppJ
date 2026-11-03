package com.nexachat.auth;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(refreshTokenRepository, new JwtProperties("test-secret", 900, 14));
    }

    private static User userWithId(UUID id) {
        User user = User.create("+84912345678", "hash", "Minh");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static RefreshToken liveToken(UUID userId, String rawToken) {
        return RefreshToken.issue(userWithId(userId), TokenService.sha256Hex(rawToken),
                "device", Instant.now().plus(Duration.ofDays(1)));
    }

    /** Stubs save() so entity ids get assigned the way the JPA UUID generator does. */
    private void stubSaveAssignsIds() {
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> {
            RefreshToken token = inv.getArgument(0);
            if (token.getId() == null) {
                ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
            }
            return token;
        });
    }

    // ---------------------------------------------------------------- issue

    @Test
    void issue_returns64CharOpaque_andPersistsSha256Only() {
        stubSaveAssignsIds();

        String raw = tokenService.issue(userWithId(UUID.randomUUID()), "JUnit-Agent");

        assertThat(raw).hasSize(64).matches("[A-Za-z0-9_-]+");

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        RefreshToken entity = saved.getValue();
        // Only the SHA-256 hex is persisted — never the usable token.
        assertThat(entity.getTokenHash()).isEqualTo(TokenService.sha256Hex(raw));
        assertThat(entity.getTokenHash()).isNotEqualTo(raw);
        assertThat(entity.getDeviceLabel()).isEqualTo("JUnit-Agent");
        assertThat(entity.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(13)));
        assertThat(entity.getRevokedAt()).isNull();
    }

    @Test
    void issue_truncatesOversizedDeviceLabelTo80() {
        stubSaveAssignsIds();

        tokenService.issue(userWithId(UUID.randomUUID()), "x".repeat(200));

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertThat(saved.getValue().getDeviceLabel()).hasSize(80);
    }

    // --------------------------------------------------------------- rotate

    @Test
    void rotate_activeToken_revokesOldAndLinksReplacement() {
        UUID userId = UUID.randomUUID();
        RefreshToken old = liveToken(userId, "rt-1");
        when(refreshTokenRepository.findByTokenHashForUpdate(TokenService.sha256Hex("rt-1")))
                .thenReturn(Optional.of(old));
        stubSaveAssignsIds();

        TokenService.RotationResult result = tokenService.rotate("rt-1", "new-device");

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.refreshToken()).isNotEqualTo("rt-1").hasSize(64);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(saved.capture());
        RefreshToken replacement = saved.getAllValues().get(0);

        assertThat(replacement.getTokenHash()).isEqualTo(TokenService.sha256Hex(result.refreshToken()));
        assertThat(replacement.getDeviceLabel()).isEqualTo("new-device");
        assertThat(old.getRevokedAt()).isNotNull();
        assertThat(old.getReplacedBy()).isEqualTo(replacement.getId());
    }

    @Test
    void rotate_unknownToken_throwsInvalidWithNoUser() {
        when(refreshTokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());

        RefreshTokenException ex = catchThrowableOfType(RefreshTokenException.class, 
                () -> tokenService.rotate("ghost", null));

        assertThat(ex.errorCode()).isEqualTo(ApiErrorCode.TOKEN_INVALID);
        assertThat(ex.userId()).isNull();
    }

    @Test
    void rotate_revokedToken_throwsReusedWithUserId_andDoesNotRotate() {
        UUID userId = UUID.randomUUID();
        RefreshToken old = liveToken(userId, "rt-1");
        old.revoke(Instant.now().minusSeconds(10));
        when(refreshTokenRepository.findByTokenHashForUpdate(TokenService.sha256Hex("rt-1")))
                .thenReturn(Optional.of(old));

        RefreshTokenException ex = catchThrowableOfType(RefreshTokenException.class, 
                () -> tokenService.rotate("rt-1", null));

        assertThat(ex.errorCode()).isEqualTo(ApiErrorCode.TOKEN_REUSED);
        assertThat(ex.userId()).isEqualTo(userId);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void rotate_expiredToken_throwsInvalidWithUserId() {
        UUID userId = UUID.randomUUID();
        RefreshToken expired = RefreshToken.issue(userWithId(userId), TokenService.sha256Hex("rt-old"),
                "device", Instant.now().minusSeconds(5));
        when(refreshTokenRepository.findByTokenHashForUpdate(TokenService.sha256Hex("rt-old")))
                .thenReturn(Optional.of(expired));

        RefreshTokenException ex = catchThrowableOfType(RefreshTokenException.class, 
                () -> tokenService.rotate("rt-old", null));

        assertThat(ex.errorCode()).isEqualTo(ApiErrorCode.TOKEN_INVALID);
        assertThat(ex.userId()).isEqualTo(userId);
        verify(refreshTokenRepository, never()).save(any());
    }

    // --------------------------------------------------------------- revoke

    @Test
    void revokeIfOwned_revokesOnlyWhenOwnerMatches() {
        UUID owner = UUID.randomUUID();
        RefreshToken token = liveToken(owner, "rt-1");
        when(refreshTokenRepository.findByTokenHash(TokenService.sha256Hex("rt-1")))
                .thenReturn(Optional.of(token));

        tokenService.revokeIfOwned("rt-1", UUID.randomUUID());
        assertThat(token.getRevokedAt()).isNull(); // someone else's token: untouched, still 204

        tokenService.revokeIfOwned("rt-1", owner);
        assertThat(token.getRevokedAt()).isNotNull();
    }

    @Test
    void revokeIfOwned_unknownToken_isNoOp() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatCode(() -> tokenService.revokeIfOwned("ghost", UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void revokeAllForUser_delegatesToBulkUpdate() {
        UUID userId = UUID.randomUUID();

        tokenService.revokeAllForUser(userId);

        verify(refreshTokenRepository).revokeAllActiveByUserId(eq(userId), any(Instant.class));
    }

    @Test
    void revokeAllForUserExcept_hashesTheKeptToken() {
        UUID userId = UUID.randomUUID();

        tokenService.revokeAllForUserExcept(userId, "keep-me");

        verify(refreshTokenRepository).revokeAllActiveByUserIdExceptTokenHash(
                eq(userId), eq(TokenService.sha256Hex("keep-me")), any(Instant.class));
    }
}
