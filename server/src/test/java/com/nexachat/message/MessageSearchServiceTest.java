package com.nexachat.message;

import com.nexachat.attachment.StorageService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.message.dto.MessageSearchProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageSearchServiceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private MessageAttachmentRepository attachmentRepository;
    @Mock private MessageReactionRepository reactionRepository;
    @Mock private ConversationRepository conversationRepository;
    @Mock private ConversationMemberRepository memberRepository;
    @Mock private PinnedMessageRepository pinnedMessageRepository;
    @Mock private StorageService storageService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private MessageService service;

    @BeforeEach
    void setUp() {
        service = new MessageService(messageRepository, attachmentRepository, reactionRepository,
                mock(ReactionEventRepository.class), conversationRepository, memberRepository,
                pinnedMessageRepository, storageService, eventPublisher, new SyncProperties());
        lenient().when(conversationRepository.existsById(conversationId)).thenReturn(true);
        lenient().when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
        lenient().when(attachmentRepository.findByMessage_IdIn(any())).thenReturn(List.of());
        lenient().when(reactionRepository.findRowsByMessageIdIn(any())).thenReturn(List.of());
        lenient().when(messageRepository.findReplyRefsByIdIn(any())).thenReturn(List.of());
    }

    @Test
    void searchEscapesLikeMetacharactersAndPreservesQuoteAsLiteralInput() {
        when(messageRepository.searchTextRows(any(), any(), any(), any())).thenReturn(List.of());

        service.search(userId, conversationId, " a%_\\' ", 20, null);

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(messageRepository).searchTextRows(
                org.mockito.ArgumentMatchers.eq(conversationId), pattern.capture(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        assertThat(pattern.getValue()).isEqualTo("%a\\%\\_\\\\'%");
    }

    @Test
    void searchAcceptsOneHundredCharactersButRejectsShortAndLongQueries() {
        when(messageRepository.searchTextRows(any(), any(), any(), any())).thenReturn(List.of());
        service.search(userId, conversationId, "x".repeat(100), null, null);

        assertThat(searchError("x").getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertThat(searchError("x".repeat(101)).getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(messageRepository, times(1)).searchTextRows(
                org.mockito.ArgumentMatchers.eq(conversationId), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void searchRequiresMembershipAndRejectsInvalidPageSize() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(false);
        assertThat(searchError("ab").getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);

        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        assertThat(searchErrorWithLimit(0).getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertThat(searchErrorWithLimit(51).getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void searchReturnsNewestFirstPageAndExclusiveNextCursor() {
        List<MessageSearchProjection> rows = List.of(projection(row(8)), projection(row(6)), projection(row(4)));
        when(messageRepository.searchTextRows(org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any())).thenReturn(rows);

        var page = service.search(userId, conversationId, "match", 2, null);

        assertThat(page.items()).extracting(response -> response.seq()).containsExactly(8L, 6L);
        assertThat(page.nextBeforeSeq()).isEqualTo(6L);
        assertThat(page.hasMore()).isTrue();
    }

    private ApiException searchError(String query) {
        return catchThrowableOfType(ApiException.class,
                () -> service.search(userId, conversationId, query, 20, null));
    }

    private ApiException searchErrorWithLimit(int limit) {
        return catchThrowableOfType(ApiException.class,
                () -> service.search(userId, conversationId, "ab", limit, null));
    }

    private MessageRow row(long seq) {
        UUID id = UUID.randomUUID();
        return new MessageRow(id, conversationId, seq, userId, "User", Message.Type.TEXT,
                "match " + seq, UUID.randomUUID(), null, Instant.now(), null, null);
    }

    private static MessageSearchProjection projection(MessageRow row) {
        return new MessageSearchProjection() {
            @Override public UUID getId() { return row.id(); }
            @Override public UUID getConversationId() { return row.conversationId(); }
            @Override public Long getSeq() { return row.seq(); }
            @Override public UUID getSenderId() { return row.senderId(); }
            @Override public String getSenderDisplayName() { return row.senderDisplayName(); }
            @Override public String getType() { return row.type().name(); }
            @Override public String getContent() { return row.content(); }
            @Override public UUID getClientMessageId() { return row.clientMessageId(); }
            @Override public UUID getReplyToId() { return row.replyToId(); }
            @Override public Instant getCreatedAt() { return row.createdAt(); }
            @Override public Instant getEditedAt() { return row.editedAt(); }
            @Override public Instant getDeletedAt() { return row.deletedAt(); }
            @Override public Boolean getMentionAll() { return row.mentionAll(); }
        };
    }
}
