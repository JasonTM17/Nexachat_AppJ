package com.nexachat.conversation;

import com.nexachat.conversation.dto.InboxConversationRow;
import com.nexachat.conversation.dto.InboxEntry;
import com.nexachat.conversation.dto.InboxMemberView;
import com.nexachat.conversation.dto.UnreadCountRow;
import com.nexachat.message.Message;
import com.nexachat.message.MessageRepository;
import com.nexachat.message.dto.MessageRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboxServiceTest {

    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private MessageRepository messageRepository;

    private InboxService inboxService;

    private final UUID userId = UUID.randomUUID();
    private final UUID partnerId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        inboxService = new InboxService(memberRepository, messageRepository);
    }

    @Test
    void listInbox_empty_returnsEmptyList() {
        when(memberRepository.findInboxConversations(userId)).thenReturn(List.of());

        assertThat(inboxService.listInbox(userId)).isEmpty();
    }

    @Test
    void listInbox_assemblesEntryWithMembersLastMessageAndUnread() {
        UUID lastMessageId = UUID.randomUUID();
        Instant now = Instant.now();
        InboxConversationRow row = new InboxConversationRow(
                conversationId, Conversation.Type.DIRECT, null, null,
                lastMessageId, 42L, now.minusSeconds(60), 40L);
        when(memberRepository.findInboxConversations(userId)).thenReturn(List.of(row));

        InboxMemberView me = new InboxMemberView(conversationId, userId, "Me", "+84901111111", null);
        InboxMemberView partner = new InboxMemberView(
                conversationId, partnerId, "Partner", "+84902222222", "/api/v1/files/avatars/x.png");
        when(memberRepository.findInboxMemberViews(List.of(conversationId)))
                .thenReturn(List.of(me, partner));

        MessageRow lastMessage = new MessageRow(
                lastMessageId, conversationId, 42L, partnerId, "Partner",
                Message.Type.TEXT, "latest message", UUID.randomUUID(), null, now, null);
        when(messageRepository.findRowsByIdIn(List.of(lastMessageId)))
                .thenReturn(List.of(lastMessage));

        when(memberRepository.countUnreadByConversation(userId, List.of(conversationId)))
                .thenReturn(List.of(new UnreadCountRow(conversationId, 2L)));

        List<InboxEntry> inbox = inboxService.listInbox(userId);

        assertThat(inbox).hasSize(1);
        InboxEntry entry = inbox.get(0);
        assertThat(entry.id()).isEqualTo(conversationId);
        assertThat(entry.type()).isEqualTo("DIRECT");
        assertThat(entry.title()).isNull();
        assertThat(entry.members()).hasSize(2);
        assertThat(entry.members().get(1).avatarUrl()).isEqualTo("/api/v1/files/avatars/x.png");
        assertThat(entry.lastMessage().seq()).isEqualTo(42);
        assertThat(entry.lastMessage().contentSnippet()).isEqualTo("latest message");
        assertThat(entry.lastMessage().senderId()).isEqualTo(partnerId);
        assertThat(entry.unreadCount()).isEqualTo(2);
        assertThat(entry.lastReadSeq()).isEqualTo(40);
    }

    @Test
    void listInbox_conversationWithoutMessages_hasNullLastMessageAndZeroUnread() {
        InboxConversationRow row = new InboxConversationRow(
                conversationId, Conversation.Type.DIRECT, null, null,
                null /* no messages yet */, null, Instant.now(), 0L);
        when(memberRepository.findInboxConversations(userId)).thenReturn(List.of(row));
        when(memberRepository.findInboxMemberViews(any())).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(any(), any())).thenReturn(List.of());

        InboxEntry entry = inboxService.listInbox(userId).get(0);

        assertThat(entry.lastMessage()).isNull();
        assertThat(entry.unreadCount()).isZero();
    }

    @Test
    void snippet_truncatesLongContent() {
        String longContent = "x".repeat(200);
        UUID lastMessageId = UUID.randomUUID();
        InboxConversationRow row = new InboxConversationRow(
                conversationId, Conversation.Type.DIRECT, null, null,
                lastMessageId, 5L, Instant.now(), 0L);
        when(memberRepository.findInboxConversations(userId)).thenReturn(List.of(row));
        when(memberRepository.findInboxMemberViews(any())).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(any(), any())).thenReturn(List.of());
        when(messageRepository.findRowsByIdIn(List.of(lastMessageId))).thenReturn(List.of(
                new MessageRow(lastMessageId, conversationId, 5L, partnerId, "P",
                        Message.Type.TEXT, longContent, UUID.randomUUID(), null,
                        Instant.now(), null)));

        InboxEntry entry = inboxService.listInbox(userId).get(0);

        assertThat(entry.lastMessage().contentSnippet())
                .hasSize(InboxService.SNIPPET_LENGTH + 1)
                .endsWith("…");
    }
}
