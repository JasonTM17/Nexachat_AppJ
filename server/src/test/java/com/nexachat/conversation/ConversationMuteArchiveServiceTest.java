package com.nexachat.conversation;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.MuteRequest;
import com.nexachat.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-member mute/archive rules: member-only authZ, fail-closed body validation,
 * and idempotent state changes on the caller's own membership row.
 *
 * <p>AuthZ order matches the rest of the codebase (conversation existence
 * 404 → membership 403 → body 400), so a non-member never learns whether a
 * body would have been valid.
 */
@ExtendWith(MockitoExtension.class)
class ConversationMuteArchiveServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final Duration MAX_MUTE = Duration.ofDays(365);

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;

    private ConversationMuteArchiveService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConversationMuteArchiveService(
                conversationRepository, memberRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ConversationMember member() {
        User user = User.create("+84901112233", "hash", "Me");
        ReflectionTestUtils.setField(user, "id", userId);
        Conversation conversation = Conversation.direct(user, "a:b");
        ReflectionTestUtils.setField(conversation, "id", conversationId);
        return ConversationMember.join(conversation, user, ConversationMember.Role.MEMBER);
    }

    private void givenConversationExists() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
    }

    private void givenMember() {
        givenConversationExists();
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.of(member()));
    }

    // ------------------------------------------------------------------ mute

    @Test
    void mute_withFutureInstant_storesExpiryAndSaves() {
        givenMember();

        service.mute(userId, conversationId, new MuteRequest(NOW.plus(Duration.ofDays(1)), null));

        verify(memberRepository).save(ArgumentMatchers.argThat(this::isMutedUntil));
    }

    @Test
    void mute_forever_storesNullExpiry() {
        givenMember();

        service.mute(userId, conversationId, new MuteRequest(null, true));

        verify(memberRepository).save(ArgumentMatchers.argThat(member ->
                member.isMuted()
                        && member.getMutedUntil() == null));
    }

    @Test
    void mute_exactlyOneYearFromNow_isAccepted() {
        givenMember();

        service.mute(userId, conversationId, new MuteRequest(NOW.plus(MAX_MUTE), null));

        verify(memberRepository).save(ArgumentMatchers.argThat(
                member -> member.isMuted()));
    }

    @Test
    void mute_oneYearAndOneSecondFromNow_isRejected() {
        givenMember();

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.mute(userId, conversationId,
                        new MuteRequest(NOW.plus(MAX_MUTE).plusSeconds(1), null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        verify(memberRepository, never()).save(any());
    }

    @Test
    void mute_pastInstant_isRejected() {
        givenMember();

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.mute(userId, conversationId,
                        new MuteRequest(NOW.minusSeconds(1), null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void mute_nowInstant_isRejected() {
        givenMember();

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.mute(userId, conversationId, new MuteRequest(NOW, null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void mute_bothForeverAndExpiry_isRejected() {
        givenMember();

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.mute(userId, conversationId,
                        new MuteRequest(NOW.plusSeconds(60), true)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void mute_neitherForeverNorExpiry_isRejected() {
        givenMember();

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.mute(userId, conversationId, new MuteRequest(null, null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void mute_foreverFalseWithoutExpiry_isRejected() {
        givenMember();

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.mute(userId, conversationId, new MuteRequest(null, false)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void mute_nullBody_isRejected() {
        givenMember();

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.mute(userId, conversationId, null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void mute_repeatedCallKeepsStateAndSucceeds() {
        givenMember();
        Instant until = NOW.plus(Duration.ofDays(1));

        service.mute(userId, conversationId, new MuteRequest(until, null));
        service.mute(userId, conversationId, new MuteRequest(until, null));

        verify(memberRepository, times(2)).save(ArgumentMatchers.argThat(this::isMutedUntil));
    }

    // ---------------------------------------------------------------- unmute

    @Test
    void unmute_clearsMuteAndSaves() {
        givenMember();

        service.unmute(userId, conversationId);

        verify(memberRepository).save(ArgumentMatchers.argThat(member ->
                !member.isMuted()
                        && member.getMutedUntil() == null));
    }

    // --------------------------------------------------------------- archive

    @Test
    void archive_setsFlagAndSaves() {
        givenMember();

        service.archive(userId, conversationId);

        verify(memberRepository).save(ArgumentMatchers.argThat(member ->
                member.isArchived()));
    }

    @Test
    void unarchive_clearsFlagAndSaves() {
        givenMember();

        service.unarchive(userId, conversationId);

        verify(memberRepository).save(ArgumentMatchers.argThat(member ->
                !member.isArchived()));
    }

    @Test
    void archive_repeatedCallKeepsFlagAndSucceeds() {
        givenMember();

        service.archive(userId, conversationId);
        service.archive(userId, conversationId);

        verify(memberRepository, times(2)).save(ArgumentMatchers.argThat(member ->
                member.isArchived()));
    }

    // ------------------------------------------------------------------ authZ

    @Test
    void mute_unknownConversation_throwsNotFoundWithoutMembershipLookup() {
        when(conversationRepository.existsById(conversationId)).thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.mute(userId, conversationId,
                        new MuteRequest(NOW.plusSeconds(60), null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.CONVERSATION_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        verify(memberRepository, never()).findByConversation_IdAndUser_Id(any(), any());
    }

    @Test
    void mute_nonMemberWithInvalidBody_throwsNotMemberNotValidation() {
        givenConversationExists();
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.mute(userId, conversationId, new MuteRequest(null, null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(memberRepository, never()).save(any());
    }

    @Test
    void archive_nonMember_throwsNotMember() {
        givenConversationExists();
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.archive(userId, conversationId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void unmute_nonMember_throwsNotMember() {
        givenConversationExists();
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.unmute(userId, conversationId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    @Test
    void unarchive_nonMember_throwsNotMember() {
        givenConversationExists();
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.unarchive(userId, conversationId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    private boolean isMutedUntil(ConversationMember member) {
        return member.isMuted()
                && NOW.plus(Duration.ofDays(1)).equals(member.getMutedUntil());
    }
}
