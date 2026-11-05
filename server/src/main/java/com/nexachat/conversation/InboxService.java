package com.nexachat.conversation;

import com.nexachat.conversation.dto.InboxConversationRow;
import com.nexachat.conversation.dto.InboxEntry;
import com.nexachat.conversation.dto.InboxMemberView;
import com.nexachat.conversation.dto.UnreadCountRow;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.message.MessageRepository;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.presence.PresenceService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    static final int MAX_INBOX_PAGE_LIMIT = 100;

    public record InboxPage(List<InboxEntry> items, String nextCursor) {
    }

    private record Cursor(long lastMessageSeq, Instant createdAt, UUID conversationId) {
    }

    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final PresenceService presenceService;

    public InboxService(ConversationMemberRepository memberRepository,
                        MessageRepository messageRepository,
                        PresenceService presenceService) {
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.presenceService = presenceService;
    }

    @Transactional(readOnly = true)
    public List<InboxEntry> listInbox(UUID userId) {
        List<InboxConversationRow> rows = memberRepository.findInboxConversations(userId);
        return toEntries(userId, rows);
    }

    /**
     * Optional keyset pagination. Omitting both parameters preserves the
     * existing unbounded inbox; a cursor without a limit returns all remaining
     * entries, while a limit returns at most 100 and supplies a next cursor.
     */
    @Transactional(readOnly = true)
    public InboxPage listInbox(UUID userId, Integer limit, String cursorToken) {
        if (limit == null && cursorToken == null) {
            return new InboxPage(listInbox(userId), null);
        }
        Cursor cursor = cursorToken == null ? null : decodeCursor(cursorToken);
        if (limit == null) {
            List<InboxConversationRow> rows = memberRepository.findInboxConversationsAfterCursor(
                    userId, cursor == null ? null : cursor.lastMessageSeq(),
                    cursor == null ? null : cursor.createdAt(),
                    cursor == null ? null : cursor.conversationId());
            return new InboxPage(toEntries(userId, rows), null);
        }

        int pageSize = normalizeLimit(limit);
        List<InboxConversationRow> rows = memberRepository.findInboxConversationsPage(
                userId, cursor == null ? null : cursor.lastMessageSeq(),
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.conversationId(),
                PageRequest.of(0, pageSize + 1));
        boolean hasMore = rows.size() > pageSize;
        List<InboxConversationRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1))
                : null;
        return new InboxPage(toEntries(userId, page), nextCursor);
    }

    private List<InboxEntry> toEntries(UUID userId, List<InboxConversationRow> rows) {
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
        Map<UUID, InboxEntry.PeerPresence> peerPresenceByConversation =
                presenceService.peerPresence(userId, conversationIds);

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
                        row.lastReadSeq(),
                        row.type() == com.nexachat.conversation.Conversation.Type.DIRECT
                                ? peerPresenceByConversation.get(row.id()) : null))
                .toList();
    }

    private static int normalizeLimit(int limit) {
        return limit < 1 ? 1 : Math.min(limit, MAX_INBOX_PAGE_LIMIT);
    }

    private static String encodeCursor(InboxConversationRow row) {
        long sortSeq = Objects.requireNonNullElse(row.lastMessageSeq(), 0L);
        String value = "v1|" + sortSeq + "|" + row.createdAt() + "|" + row.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String token) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] fields = value.split("\\|", -1);
            if (fields.length != 4 || !"v1".equals(fields[0])) {
                throw new IllegalArgumentException("invalid cursor version");
            }
            long seq = Long.parseLong(fields[1]);
            Instant createdAt = Instant.parse(fields[2]);
            UUID conversationId = UUID.fromString(fields[3]);
            if (seq < 0) {
                throw new IllegalArgumentException("invalid cursor sequence");
            }
            return new Cursor(seq, createdAt, conversationId);
        } catch (RuntimeException ex) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Invalid inbox cursor");
        }
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
                row.seq(), row.type().name(), snippet(row.content()), row.senderId(), row.createdAt(), row.deletedAt());
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
