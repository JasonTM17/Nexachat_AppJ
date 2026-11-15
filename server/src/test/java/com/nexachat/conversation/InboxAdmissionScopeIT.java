package com.nexachat.conversation;

import com.nexachat.conversation.dto.InboxEntry;
import com.nexachat.conversation.dto.OpenDirectRequest;
import com.nexachat.message.MessageService;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.SendMessageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inbox preview and unread badge obey the same admission boundary as
 * history: a member sees only messages created at or after their
 * {@code conversation_members.joined_at}. The denormalized
 * {@code last_message_*} pointer is conversation-wide, so the read model must
 * withhold a preview whose target predates the caller's admission; the unread
 * count applies the same predicate history already does.
 */
class InboxAdmissionScopeIT extends ConcurrentDatabaseITSupport {
    @Autowired private InboxService inbox;
    @Autowired private MessageService messages;
    @Autowired private ConversationService conversations;

    @Test
    void lateJoinerSeesEmptyPreviewAndZeroUnreadWhileOnlyPreAdmissionMessagesExist() {
        var owner = user("Owner");
        var reader = user("Reader");
        UUID conversation = group(owner, reader);
        send(owner.getId(), conversation, "needle pre-admission preview");
        moveAdmissionBoundary(conversation, owner.getId(), reader.getId());

        InboxEntry entry = entryFor(reader.getId(), conversation);
        assertThat(entry.lastMessage()).isNull();
        assertThat(entry.unreadCount()).isZero();
        assertThat(entry.lastMessageEncrypted()).isFalse();

        InboxEntry ownerEntry = entryFor(owner.getId(), conversation);
        assertThat(ownerEntry.lastMessage()).isNotNull();
        assertThat(ownerEntry.lastMessage().contentSnippet())
                .isEqualTo("needle pre-admission preview");
    }

    @Test
    void lateJoinerPreviewIsTheNewestAdmittedMessageAndUnreadCountsOnlyAdmitted() {
        var owner = user("Owner");
        var reader = user("Reader");
        UUID conversation = group(owner, reader);
        send(owner.getId(), conversation, "needle pre-admission preview");
        Instant joined = Instant.now();
        backdateConversation(conversation, joined, owner.getId(), reader.getId());
        MessageResponse admitted = send(owner.getId(), conversation, "needle admitted latest");
        jdbc.update("UPDATE messages SET created_at=? WHERE id=?",
                Timestamp.from(joined.plusSeconds(1)), admitted.id());

        InboxEntry entry = entryFor(reader.getId(), conversation);
        assertThat(entry.lastMessage()).isNotNull();
        assertThat(entry.lastMessage().seq()).isEqualTo(admitted.seq());
        assertThat(entry.lastMessage().contentSnippet()).isEqualTo("needle admitted latest");
        assertThat(entry.lastMessage().senderId()).isEqualTo(owner.getId());
        assertThat(entry.unreadCount()).isEqualTo(1);
    }

    @Test
    void directInboxPreviewAndUnreadStayUnscopedForOriginalMembers() {
        var caller = user("Caller");
        var peer = user("Peer");
        UUID conversation = conversations
                .openDirect(caller.getId(), new OpenDirectRequest(null, peer.getId()))
                .body().id();
        send(caller.getId(), conversation, "needle direct hello");

        InboxEntry entry = entryFor(peer.getId(), conversation);
        assertThat(entry.lastMessage()).isNotNull();
        assertThat(entry.lastMessage().contentSnippet()).isEqualTo("needle direct hello");
        assertThat(entry.unreadCount()).isEqualTo(1);
    }

    private InboxEntry entryFor(UUID userId, UUID conversation) {
        return inbox.listInbox(userId, null).stream()
                .filter(entry -> entry.id().equals(conversation))
                .findFirst()
                .orElseThrow(() -> new AssertionError("conversation missing from inbox"));
    }

    private MessageResponse send(UUID userId, UUID conversation, String content) {
        return messages.send(userId, conversation, new SendMessageRequest(
                UUID.randomUUID(), "TEXT", content, null, List.of())).message();
    }

    /**
     * Every stored message lands one second before {@code joined}; the reader's
     * member row is stamped {@code joined} and the owner's one minute earlier —
     * the same deterministic boundary {@code HistoryMembershipScopeIT} uses.
     */
    private void moveAdmissionBoundary(UUID conversation, UUID ownerId, UUID readerId) {
        backdateConversation(conversation, Instant.now(), ownerId, readerId);
    }

    private void backdateConversation(UUID conversation, Instant joined, UUID ownerId, UUID readerId) {
        jdbc.update("UPDATE messages SET created_at=? WHERE conversation_id=?",
                Timestamp.from(joined.minusSeconds(1)), conversation);
        jdbc.update("UPDATE conversation_members SET joined_at=? WHERE conversation_id=? AND user_id=?",
                Timestamp.from(joined), conversation, readerId);
        jdbc.update("UPDATE conversation_members SET joined_at=? WHERE conversation_id=? AND user_id=?",
                Timestamp.from(joined.minusSeconds(60)), conversation, ownerId);
    }
}
