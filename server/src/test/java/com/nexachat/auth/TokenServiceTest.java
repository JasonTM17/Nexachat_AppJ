package com.nexachat.auth;

import com.nexachat.auth.dto.AuthResponse;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import com.nexachat.user.dto.UserResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private EntityManager entityManager;
    @Mock private Query databaseTimeQuery;
    @Mock private RefreshReplayCipher replayCipher;
    @Mock private AuthResponseFactory responseFactory;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(refreshTokenRepository, userRepository, entityManager,
                new JwtProperties("test-secret", 900, 14), replayCipher, responseFactory,
                (boundary, operation, userId, tokenId) -> { });
        lenient().when(entityManager.createNativeQuery("SELECT clock_timestamp()"))
                .thenReturn(databaseTimeQuery);
        lenient().when(databaseTimeQuery.getSingleResult())
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        lenient().when(responseFactory.create(any(), anyString())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            String refresh = invocation.getArgument(1);
            return new AuthResponse(UserResponse.from(user), "access", 900, refresh, 86_400, true, null);
        });
    }

    private static User userWithId(UUID id) {
        User user = User.create("+84912345678", "hash", "Minh");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static RefreshToken liveToken(UUID userId, String rawToken) {
        RefreshToken token = RefreshToken.issue(userWithId(userId), TokenService.sha256Hex(rawToken),
                "device", NOW.plus(Duration.ofDays(1)), NOW.minusSeconds(60));
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        return token;
    }

    private void stubSaveAssignsIds() {
        lenient().when(refreshTokenRepository.save(any()))
                .thenAnswer(invocation -> assignId(invocation.getArgument(0)));
        lenient().when(refreshTokenRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> assignId(invocation.getArgument(0)));
    }

    private static RefreshToken assignId(RefreshToken token) {
        if (token.getId() == null) {
            ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        }
        return token;
    }

    private void stubUserLock(UUID userId) {
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(userWithId(userId)));
    }

    @Test
    void issue_returns64CharOpaque_andPersistsSha256Only() {
        UUID userId = UUID.randomUUID();
        User user = userWithId(userId);
        stubUserLock(userId);
        stubSaveAssignsIds();

        String raw = tokenService.issue(user, "JUnit-Agent");

        assertThat(raw).hasSize(64).matches("[A-Za-z0-9_-]+");
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        RefreshToken entity = saved.getValue();
        assertThat(entity.getTokenHash()).isEqualTo(TokenService.sha256Hex(raw)).isNotEqualTo(raw);
        assertThat(entity.getDeviceLabel()).isEqualTo("JUnit-Agent");
        assertThat(entity.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getRevokedAt()).isNull();
        verify(userRepository).findByIdForUpdate(userId);
    }

    @Test
    void issue_truncatesOversizedDeviceLabelTo80() {
        UUID userId = UUID.randomUUID();
        stubUserLock(userId);
        stubSaveAssignsIds();

        tokenService.issue(userWithId(userId), "x".repeat(200));

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertThat(saved.getValue().getDeviceLabel()).hasSize(80);
    }

    @Test
    void rotate_activeToken_revokesOldAndLinksReplacement() {
        UUID userId = UUID.randomUUID();
        User user = userWithId(userId);
        RefreshToken old = liveToken(userId, "rt-1");
        when(refreshTokenRepository.findUserIdByTokenHash(TokenService.sha256Hex("rt-1")))
                .thenReturn(Optional.of(userId));
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByTokenHashForUpdate(TokenService.sha256Hex("rt-1")))
                .thenReturn(Optional.of(old));
        stubSaveAssignsIds();

        TokenService.RotationResult result = tokenService.rotate("rt-1", null, "new-device");

        assertThat(result.userId()).isEqualTo(userId);
        String successorRaw = result.response().refreshToken();
        assertThat(successorRaw).isNotEqualTo("rt-1").hasSize(64);
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).saveAndFlush(saved.capture());
        RefreshToken replacement = saved.getAllValues().get(0);
        assertThat(replacement.getTokenHash()).isEqualTo(TokenService.sha256Hex(successorRaw));
        assertThat(replacement.getDeviceLabel()).isEqualTo("new-device");
        assertThat(old.getRevokedAt()).isEqualTo(NOW);
        assertThat(old.getReplacedBy()).isEqualTo(replacement.getId());
        verify(userRepository).findByIdForUpdate(userId);
        verify(entityManager, times(1)).createNativeQuery("SELECT clock_timestamp()");
    }

    @Test
    void rotate_unknownToken_throwsInvalidWithNoUser() {
        when(refreshTokenRepository.findUserIdByTokenHash(TokenService.sha256Hex("ghost")))
                .thenReturn(Optional.empty());

        RefreshTokenException ex = catchThrowableOfType(RefreshTokenException.class,
                () -> tokenService.rotate("ghost", null, null));

        assertThat(ex.errorCode()).isEqualTo(ApiErrorCode.TOKEN_INVALID);
        assertThat(ex.userId()).isNull();
        verify(userRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void rotate_revokedToken_throwsReusedWithUserId_andDoesNotRotate() {
        UUID userId = UUID.randomUUID();
        RefreshToken old = liveToken(userId, "rt-1");
        old.revoke(NOW.minusSeconds(10));
        when(refreshTokenRepository.findUserIdByTokenHash(TokenService.sha256Hex("rt-1")))
                .thenReturn(Optional.of(userId));
        stubUserLock(userId);
        when(refreshTokenRepository.findByTokenHashForUpdate(TokenService.sha256Hex("rt-1")))
                .thenReturn(Optional.of(old));

        RefreshTokenException ex = catchThrowableOfType(RefreshTokenException.class,
                () -> tokenService.rotate("rt-1", null, null));

        assertThat(ex.errorCode()).isEqualTo(ApiErrorCode.TOKEN_REUSED);
        assertThat(ex.userId()).isEqualTo(userId);
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).saveAndFlush(any());
    }

    @Test
    void rotate_expiredToken_throwsInvalidWithUserId() {
        UUID userId = UUID.randomUUID();
        RefreshToken expired = RefreshToken.issue(userWithId(userId), TokenService.sha256Hex("rt-old"),
                "device", NOW.minusSeconds(5), NOW.minusSeconds(60));
        ReflectionTestUtils.setField(expired, "id", UUID.randomUUID());
        when(refreshTokenRepository.findUserIdByTokenHash(TokenService.sha256Hex("rt-old")))
                .thenReturn(Optional.of(userId));
        stubUserLock(userId);
        when(refreshTokenRepository.findByTokenHashForUpdate(TokenService.sha256Hex("rt-old")))
                .thenReturn(Optional.of(expired));

        RefreshTokenException ex = catchThrowableOfType(RefreshTokenException.class,
                () -> tokenService.rotate("rt-old", null, null));

        assertThat(ex.errorCode()).isEqualTo(ApiErrorCode.TOKEN_INVALID);
        assertThat(ex.userId()).isEqualTo(userId);
        verify(refreshTokenRepository, never()).saveAndFlush(any());
    }

    @Test
    void revokeIfOwned_revokesOnlyWhenOwnerMatches() {
        UUID owner = UUID.randomUUID();
        RefreshToken token = liveToken(owner, "rt-1");
        when(refreshTokenRepository.findUserIdByTokenHash(TokenService.sha256Hex("rt-1")))
                .thenReturn(Optional.of(owner));
        when(userRepository.findByIdForUpdate(owner)).thenReturn(Optional.of(userWithId(owner)));
        when(refreshTokenRepository.findAllByUserIdForUpdate(owner)).thenReturn(List.of(token));

        tokenService.revokeIfOwned("rt-1", UUID.randomUUID());
        assertThat(token.getRevokedAt()).isNull();

        tokenService.revokeIfOwned("rt-1", owner);
        assertThat(token.getRevokedAt()).isEqualTo(NOW);
    }

    @Test
    void revokeIfOwned_unknownToken_isNoOp() {
        when(refreshTokenRepository.findUserIdByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatCode(() -> tokenService.revokeIfOwned("ghost", UUID.randomUUID())).doesNotThrowAnyException();
        verify(userRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void revokeAllForUser_delegatesToBulkUpdateAndInvalidatesReplay() {
        UUID userId = UUID.randomUUID();
        stubUserLock(userId);

        tokenService.revokeAllForUser(userId);

        verify(refreshTokenRepository).revokeAllActiveByUserId(eq(userId), eq(NOW));
        verify(refreshTokenRepository).invalidateReplayPayloadsByUserId(userId);
    }

    @Test
    void passwordKeepPredecessorResolvesToItsLiveDescendant() {
        UUID userId = UUID.randomUUID();
        RefreshToken predecessor = liveToken(userId, "rt-old");
        RefreshToken current = liveToken(userId, "rt-current");
        predecessor.revoke(NOW.minusSeconds(5));
        predecessor.markReplacedBy(current.getId());
        stubUserLock(userId);
        when(refreshTokenRepository.findAllByUserIdForUpdate(userId)).thenReturn(List.of(predecessor, current));

        tokenService.revokeAllForUserExcept(userId, "rt-old");

        verify(refreshTokenRepository).revokeAllActiveByUserIdExceptTokenHash(
                eq(userId), eq(current.getTokenHash()), eq(NOW));
        verify(refreshTokenRepository).invalidateReplayPayloadsByUserId(userId);
    }

    @Test
    void unknownPasswordKeepValueRevokesAllActiveSessions() {
        UUID userId = UUID.randomUUID();
        stubUserLock(userId);
        when(refreshTokenRepository.findAllByUserIdForUpdate(userId)).thenReturn(List.of());

        tokenService.revokeAllForUserExcept(userId, "foreign-or-unknown");

        verify(refreshTokenRepository).revokeAllActiveByUserId(eq(userId), eq(NOW));
        verify(refreshTokenRepository, never()).revokeAllActiveByUserIdExceptTokenHash(any(), anyString(), any());
    }
}
