package com.nexachat.recovery;

import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailOtpClockBoundaryTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    @Test
    void codeExpiresAtTheExactExpiryInstant() {
        UserRepository users = mock(UserRepository.class);
        EmailOtpRepository otps = mock(EmailOtpRepository.class);
        OtpCodeHasher hasher = mock(OtpCodeHasher.class);
        OtpAttemptRecorder attempts = mock(OtpAttemptRecorder.class);
        User user = verifiedUser();
        EmailOtpRecord candidate = new EmailOtpRecord(
                UUID.randomUUID(), USER_ID, OtpPurpose.DELETE_ACCOUNT, "owner@example.com",
                "a".repeat(64), NOW, 0, null);
        when(otps.findActive(USER_ID, OtpPurpose.DELETE_ACCOUNT, "owner@example.com"))
                .thenReturn(Optional.of(candidate));
        EmailOtpService service = service(users, otps, hasher, attempts);

        assertThat(service.consume(user, OtpPurpose.DELETE_ACCOUNT, "123456")).isFalse();

        verify(hasher).dummyCompare(OtpPurpose.DELETE_ACCOUNT, "123456");
        verify(attempts, never()).recordWrongAttempt(any(), any());
        verify(otps, never()).consume(any(), any());
    }

    @Test
    void resendCooldownAllowsExactlySixtySecondsAndRejectsOneNanosecondBefore() {
        UserRepository users = mock(UserRepository.class);
        EmailOtpRepository otps = mock(EmailOtpRepository.class);
        OtpCodeHasher hasher = mock(OtpCodeHasher.class);
        OtpAttemptRecorder attempts = mock(OtpAttemptRecorder.class);
        User user = verifiedUser();
        when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(users.existsVerifiedEmailForOtherUser("next@example.com", USER_ID)).thenReturn(false);
        when(hasher.newCode()).thenReturn("123456");
        when(hasher.hash(USER_ID, OtpPurpose.VERIFY_EMAIL, "123456")).thenReturn("b".repeat(64));
        when(otps.latestCreatedAt(USER_ID, OtpPurpose.VERIFY_EMAIL)).thenReturn(NOW.minusSeconds(60));
        EmailOtpService service = service(users, otps, hasher, attempts);

        assertThat(service.beginEmailVerification(USER_ID, "next@example.com").code()).isEqualTo("123456");
        verify(otps).insert(any(), eq(USER_ID), eq(OtpPurpose.VERIFY_EMAIL), eq("next@example.com"),
                eq("b".repeat(64)), eq(NOW.plus(EmailOtpService.CODE_TTL)), eq(NOW));

        when(otps.latestCreatedAt(USER_ID, OtpPurpose.VERIFY_EMAIL))
                .thenReturn(NOW.minusSeconds(60).plusNanos(1));
        assertThatThrownBy(() -> service.beginEmailVerification(USER_ID, "next@example.com"))
                .isInstanceOf(com.nexachat.common.error.ApiException.class);
    }

    private static EmailOtpService service(UserRepository users,
                                           EmailOtpRepository otps,
                                           OtpCodeHasher hasher,
                                           OtpAttemptRecorder attempts) {
        return new EmailOtpService(users, otps, hasher, attempts, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static User verifiedUser() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        when(user.getEmail()).thenReturn("owner@example.com");
        when(user.getDeletedAt()).thenReturn(null);
        when(user.hasVerifiedEmail()).thenReturn(true);
        return user;
    }
}
