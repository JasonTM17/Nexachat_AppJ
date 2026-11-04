package com.nexachat.conversation;

import com.nexachat.conversation.dto.InboxConversationRow;
import com.nexachat.conversation.dto.InboxEntry;
import com.nexachat.conversation.dto.InboxMemberView;
import com.nexachat.conversation.dto.UnreadCountRow;
import com.nexachat.message.MessageRepository;
import com.nexachat.message.dto.MessageRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Inbox read model for {@code GET /conversations}: three batched queries —
 * member rows ordered by {@code lastMessageSeq DESC}, member views for all
 * conversations, unread counts grouped per conversation — plus one IN query
 * for the last-message rows. No N+1 (plan §9.3.7).
 */
@Service
public class InboxService {

    /** Preview length for {@code contentSnippet}. */
    static final int SNIPPET_LENGTH = 80;

    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;

    public InboxService(ConversationMemberRepository memberRepository,
                        MessageRepository messageRepository) {
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional(readOnly = true)
    public List<InboxEntry> listInbox(UUID userId) {
        List<InboxConversationRow> rows = memberRepository.findInboxConversations(userId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> conversationIds = rows.stream().map(InboxConversationRow::id).toList();

        Map<UUID, List<InboxEntry.Member>> membersByConversation =
                memberRepository.findInboxMemberViews(conversationIds).stream()
                        .collect(Collectors.groupingBy(
                                InboxMemberView::conversationId,
                                Collectors.mapping(InboxService::toMember, Collectors.toList())));

        List<UUID> lastMessageIds = rows.stream()
                .map(InboxConversationRow::lastMessageId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, MessageRow> lastMessageById = lastMessageIds.isEmpty()
                ? Map.of()
                : messageRepository.findRowsByIdIn(lastMessageIds).stream()
                        .collect(Collectors.toMap(MessageRow::id, row -> row));

        Map<UUID, Long> unreadByConversation =
                memberRepository.countUnreadByConversation(userId, conversationIds).stream()
                        .collect(Collectors.toMap(
                                UnreadCountRow::conversationId, UnreadCountRow::unreadCount));

        return rows.stream()
                .map(row -> new InboxEntry(
                        row.id(),
                        row.type().name(),
                        row.title(),
                        row.avatarUrl(),
                        membersByConversation.getOrDefault(row.id(), List.of()),
                        toLastMessage(row.lastMessageId() == null
                                ? null
                                : lastMessageById.get(row.lastMessageId())),
                        unreadByConversation.getOrDefault(row.id(), 0L),
                        row.lastReadSeq()))
                .toList();
    }

    private static InboxEntry.Member toMember(InboxMemberView view) {
        return new InboxEntry.Member(
                view.userId(), view.displayName(), view.phone(), view.avatarUrl(),
                view.role().name());
    }

    private static InboxEntry.LastMessage toLastMessage(MessageRow row) {
        if (row == null) {
            return null;
        }
        return new InboxEntry.LastMessage(
                row.seq(), row.type().name(), snippet(row.content()), row.senderId(), row.createdAt());
    }

    private static String snippet(String content) {
        if (content == null) {
            return null;
        }
        return content.length() > SNIPPET_LENGTH
                ? content.substring(0, SNIPPET_LENGTH) + "…"
                : content;
    }
}
