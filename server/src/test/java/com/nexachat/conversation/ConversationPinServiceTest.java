package com.nexachat.conversation;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-member conversation pin rules: member-only authZ in the codebase order
 * (404 before 403 before 409), idempotent verbs, the five-pin ceiling and the
 * caller's own row as the only mutation target.
 */
@ExtendWith(MockitoExtension.class)
class ConversationPinServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private UserRepository userRepository;

    private ConversationPinService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConversationPinService(conversationRepository, memberRepository,
                userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private User user() {
        User user = User.create("+84901112233", "hash", "Me");
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    private ConversationMember member() {
        User user = user();
        Conversation conversation = Conversation.direct(user, "a:b");
        ReflectionTestUtils.setField(conversation, "id", conversationId);
        return ConversationMember.join(conversation, user, ConversationMember.Role.MEMBER);
    }

    private void givenConversationExists() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
    }

    private void givenLockedUser() {
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user()));
    }

    private void givenMember(ConversationMember member) {
        givenConversationExists();
        givenLockedUser();
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.of(member));
    }

    /** Unpin does not need the pin-ceiling lock. */
    private void givenMemberWithoutLock(ConversationMember member) {
        givenConversationExists();
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.of(member));
    }

    // ------------------------------------------------------------------- pin

    @Test
    void pin_setsPinnedAtFromTheClockAndSavesTheCallersOwnRow() {
        ConversationMember member = member();
        givenMember(member);
        when(memberRepository.countPinnedByUserId(userId)).thenReturn(0L);

        service.pin(userId, conversationId);

        verify(memberRepository).save(ArgumentMatchers.argThat(saved ->
                saved.isPinned() && NOW.equals(saved.getPinnedAt())));
    }

    @Test
    void pin_fourExistingPinsAllowsTheFifth() {
        givenMember(member());
        when(memberRepository.countPinnedByUserId(userId)).thenReturn(4L);

        service.pin(userId, conversationId);

        verify(memberRepository).save(ArgumentMatchers.argThat(ConversationMember::isPinned));
    }

    @Test
    void pin_atFiveExistingPins_isRejectedWithConversationPinLimit() {
        givenMember(member());
        when(memberRepository.countPinnedByUserId(userId)).thenReturn(5L);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.pin(userId, conversationId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.CONVERSATION_PIN_LIMIT);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        verify(memberRepository, never()).save(any());
    }

    @Test
    void pin_alreadyPinned_isAnIdempotentNoOpEvenAtTheCeiling() {
        ConversationMember member = member();
        member.pin(NOW.minusSeconds(60));
        givenMember(member);

        service.pin(userId, conversationId);

        verify(memberRepository, never()).countPinnedByUserId(userId);
        verify(memberRepository, never()).save(any());
        assertThat(member.getPinnedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void pin_unknownConversation_is404WithoutTakingAUserLock() {
        when(conversationRepository.existsById(conversationId)).thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.pin(userId, conversationId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.CONVERSATION_NOT_FOUND);
        verify(userRepository, never()).findByIdForUpdate(any());
        verify(memberRepository, never()).save(any());
    }

    @Test
    void pin_nonMember_is403WithoutCountingOrSaving() {
        givenConversationExists();
        givenLockedUser();
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.pin(userId, conversationId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        verify(memberRepository, never()).countPinnedByUserId(userId);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void pin_missingAccount_is404() {
        givenConversationExists();
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.pin(userId, conversationId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
        verify(memberRepository, never()).save(any());
    }

    // ----------------------------------------------------------------- unpin

    @Test
    void unpin_clearsPinnedAndPinnedAtAndSaves() {
        ConversationMember member = member();
        member.pin(NOW);
        givenMemberWithoutLock(member);

        service.unpin(userId, conversationId);

        verify(memberRepository).save(ArgumentMatchers.argThat(saved ->
                !saved.isPinned() && saved.getPinnedAt() == null));
    }

    @Test
    void unpin_neverPinned_isIdempotent() {
        givenMemberWithoutLock(member());

        service.unpin(userId, conversationId);

        verify(memberRepository).save(ArgumentMatchers.argThat(saved ->
                !saved.isPinned() && saved.getPinnedAt() == null));
    }

    @Test
    void unpin_unknownConversation_is404() {
        when(conversationRepository.existsById(conversationId)).thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.unpin(userId, conversationId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.CONVERSATION_NOT_FOUND);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void unpin_nonMember_is403() {
        givenConversationExists();
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.unpin(userId, conversationId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        verify(memberRepository, never()).save(any());
    }
}
