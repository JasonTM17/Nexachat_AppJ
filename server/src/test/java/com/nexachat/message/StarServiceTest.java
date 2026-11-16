package com.nexachat.message;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.message.dto.StarRef;
import com.nexachat.message.dto.StarredPageResponse;
import com.nexachat.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StarService} unit tests — authorization at write time, the
 * viewer-scoped boundary at read time, and idempotent mutations.
 */
@ExtendWith(MockitoExtension.class)
class StarServiceTest {

    @Mock
    private MessageStarRepository starRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private MessageService messageService;

    private StarService starService;

    private final UUID userId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();
    private final Instant createdAt = Instant.parse("2026-11-15T10:00:00Z");

    @BeforeEach
    void setUp() {
        starService = new StarService(
                starRepository, messageRepository, memberRepository, messageService);
    }

    private Message message(boolean peerSuppressed) {
        Message message = mock(Message.class);
        User sender = mock(User.class);
        Conversation conversation = mock(Conversation.class);
        lenient().when(sender.getId()).thenReturn(senderId);
        lenient().when(conversation.getId()).thenReturn(conversationId);
        lenient().when(message.isPeerSuppressed()).thenReturn(peerSuppressed);
        lenient().when(message.getSender()).thenReturn(sender);
        lenient().when(message.getConversation()).thenReturn(conversation);
        lenient().when(message.getCreatedAt()).thenReturn(createdAt);
        return message;
    }

    // -------------------------------------------------------------------- star

    @Test
    void star_missingMessage_isMessageNotFound() {
        when(messageRepository.findById(messageId)).thenReturn(Optional.empty());
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> starService.star(userId, messageId));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void star_suppressedRow_isMessageNotFoundForNonSender() {
        Message suppressed = message(true);
        when(messageRepository.findById(messageId))
                .thenReturn(Optional.of(suppressed));
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> starService.star(userId, messageId));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND);
        verify(starRepository, never()).insertIgnoringConflict(any(), any());
    }

    @Test
    void star_suppressedRow_isStarrableByItsSender() {
        Message suppressed = message(true);
        lenient().when(suppressed.getSender().getId()).thenReturn(userId);
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(suppressed));
        when(memberRepository.existsAdmittedAt(conversationId, userId, createdAt)).thenReturn(true);

        starService.star(userId, messageId);
        verify(starRepository).insertIgnoringConflict(userId, messageId);
    }

    @Test
    void star_messagePredatingMembership_isNotMember() {
        Message visible = message(false);
        when(messageRepository.findById(messageId))
                .thenReturn(Optional.of(visible));
        when(memberRepository.existsAdmittedAt(conversationId, userId, createdAt)).thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> starService.star(userId, messageId));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    @Test
    void star_happy_insertsIgnoringConflict() {
        Message visible = message(false);
        when(messageRepository.findById(messageId))
                .thenReturn(Optional.of(visible));
        when(memberRepository.existsAdmittedAt(conversationId, userId, createdAt)).thenReturn(true);

        starService.star(userId, messageId);
        verify(starRepository).insertIgnoringConflict(userId, messageId);
    }

    // ------------------------------------------------------------------ unstar

    @Test
    void unstar_deletesTheRowIdempotently() {
        when(starRepository.deleteOne(userId, messageId)).thenReturn(0);
        starService.unstar(userId, messageId);
        verify(starRepository).deleteOne(userId, messageId);
    }

    // ------------------------------------------------------------ listStarred

    @Test
    void listStarred_returnsRowsInStarOrderAndDropsInvisible() {
        UUID visibleId = UUID.randomUUID();
        UUID hiddenId = UUID.randomUUID();   // e.g. left the conversation
        Instant t1 = Instant.parse("2026-11-15T11:00:00Z");
        Instant t2 = Instant.parse("2026-11-15T10:00:00Z");
        when(starRepository.findStarredRefsFirst(eq(userId), any(Pageable.class)))
                .thenReturn(List.of(new StarRef(visibleId, t1), new StarRef(hiddenId, t2)));

        MessageRow visibleRow = new MessageRow(
                visibleId, conversationId, 5L, senderId, "Sender", Message.Type.TEXT,
                "hi", null, null, createdAt, null, null, false, 1L, 1L);
        when(messageRepository.findStarredRowsByIdIn(anyCollection(), eq(userId)))
                .thenReturn(List.of(visibleRow));   // hiddenId filtered out by the boundary
        MessageResponse hydrated = mock(MessageResponse.class);
        lenient().when(hydrated.id()).thenReturn(visibleId);
        when(messageService.toResponses(any(), eq(userId))).thenReturn(List.of(hydrated));

        StarredPageResponse page = starService.listStarred(userId, null, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).starredAt()).isEqualTo(t1);
        assertThat(page.items().get(0).message()).isSameAs(hydrated);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void listStarred_fullPage_mintsCursorFromLastRef() {
        Instant t1 = Instant.parse("2026-11-15T11:00:00Z");
        Instant t2 = Instant.parse("2026-11-15T10:00:00Z");
        Instant t3 = Instant.parse("2026-11-15T09:00:00Z");
        List<StarRef> refs = List.of(
                new StarRef(UUID.randomUUID(), t1),
                new StarRef(UUID.randomUUID(), t2),
                new StarRef(UUID.randomUUID(), t3));
        when(starRepository.findStarredRefsFirst(eq(userId), any(Pageable.class)))
                .thenReturn(refs);
        when(messageRepository.findStarredRowsByIdIn(anyCollection(), eq(userId)))
                .thenReturn(List.of());
        when(messageService.toResponses(any(), eq(userId))).thenReturn(List.of());

        StarredPageResponse page = starService.listStarred(userId, 2, null);
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    void listStarred_badCursor_isValidationFailed() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> starService.listStarred(userId, null, "not-a-cursor"));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }
}
