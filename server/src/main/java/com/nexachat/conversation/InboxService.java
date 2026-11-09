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
import com.nexachat.summary.ConversationAiOptOuts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Inbox read model for {@code GET /conversations}: three batched queries —
 * member rows ordered by the caller's {@code pinned} flag and then
 * {@code lastMessageSeq DESC}, member views for all conversations, unread
 * counts grouped per conversation — plus one IN query for the last-message
 * rows. No N+1 (plan §9.3.7).
 *
 * <p>The optional {@code archived} filter selects the default (non-archived)
 * view or the archived view; omitting it means "not archived", which keeps
 * archived conversations out of the inbox clients already know how to page.
 * The keyset cursor carries the pinned rank so a page boundary inside the
 * pinned group stays stable.
 */
@Service
public class InboxService {

    /** Preview length for {@code contentSnippet}. */
    static final int SNIPPET_LENGTH = 80;
    static final int MAX_INBOX_PAGE_LIMIT = 100;

    public record InboxPage(List<InboxEntry> items, String nextCursor) {
    }

    private record Cursor(boolean pinned, long lastMessageSeq, Instant createdAt,
                          UUID conversationId) {
    }

    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final PresenceService presenceService;
    private final ConversationAiOptOuts aiOptOuts;
    private final Clock clock;

    @Autowired
    public InboxService(ConversationMemberRepository memberRepository,
                        MessageRepository messageRepository,
                        PresenceService presenceService,
                        ConversationAiOptOuts aiOptOuts) {
        this(memberRepository, messageRepository, presenceService, aiOptOuts, Clock.systemUTC());
    }

    InboxService(ConversationMemberRepository memberRepository,
                 MessageRepository messageRepository,
                 PresenceService presenceService,
                 Clock clock) {
        this(memberRepository, messageRepository, presenceService, ConversationAiOptOuts.none(), clock);
    }

    InboxService(ConversationMemberRepository memberRepository,
                 MessageRepository messageRepository,
                 PresenceService presenceService,
                 ConversationAiOptOuts aiOptOuts,
                 Clock clock) {
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.presenceService = presenceService;
        this.aiOptOuts = aiOptOuts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<InboxEntry> listInbox(UUID userId, Boolean archived) {
        List<InboxConversationRow> rows =
                memberRepository.findInboxConversations(userId, archivedFilter(archived));
        return toEntries(userId, rows);
    }

    /**
     * Optional keyset pagination. Omitting both parameters preserves the
     * existing unbounded inbox; a cursor without a limit returns all remaining
     * entries, while a limit returns at most 100 and supplies a next cursor.
     * The {@code archived} filter applies to every shape so a client paging the
     * archived view cannot fall back to the default view mid-walk.
     */
    @Transactional(readOnly = true)
    public InboxPage listInbox(UUID userId, Integer limit, String cursorToken, Boolean archived) {
        boolean includeArchived = archivedFilter(archived);
        if (limit == null && cursorToken == null) {
            return new InboxPage(listInbox(userId, includeArchived), null);
        }
        Cursor cursor = cursorToken == null ? null : decodeCursor(cursorToken);
        if (limit == null) {
            List<InboxConversationRow> rows = memberRepository.findInboxConversationsAfterCursor(
                    userId, cursor == null ? null : cursor.pinned(),
                    cursor == null ? null : cursor.lastMessageSeq(),
                    cursor == null ? null : cursor.createdAt(),
                    cursor == null ? null : cursor.conversationId(),
                    includeArchived);
            return new InboxPage(toEntries(userId, rows), null);
        }

        int pageSize = normalizeLimit(limit);
        List<InboxConversationRow> rows = memberRepository.findInboxConversationsPage(
                userId, cursor == null ? null : cursor.pinned(),
                cursor == null ? null : cursor.lastMessageSeq(),
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.conversationId(),
                includeArchived,
                PageRequest.of(0, pageSize + 1));
        boolean hasMore = rows.size() > pageSize;
        List<InboxConversationRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1))
                : null;
        return new InboxPage(toEntries(userId, page), nextCursor);
    }

    /** An omitted filter means the default (non-archived) inbox view. */
    private static boolean archivedFilter(Boolean archived) {
        return Boolean.TRUE.equals(archived);
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

        Set<UUID> optedOutConversations = aiOptOuts.optedOutConversationIds(userId);
        Instant now = clock.instant();
        return rows.stream()
                .map(row -> {
                    // A mute whose instant has passed is not muted any more; the
                    // expiry is lazy so no sweep job has to clear the flag.
                    boolean muted = row.muted()
                            && (row.mutedUntil() == null || row.mutedUntil().isAfter(now));
                    return new InboxEntry(
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
                                    ? peerPresenceByConversation.get(row.id()) : null,
                            muted,
                            muted ? row.mutedUntil() : null,
                            row.archived(),
                            optedOutConversations.contains(row.id()),
                            row.pinned(),
                            row.pinned() ? row.pinnedAt() : null);
                })
                .toList();
    }

    private static int normalizeLimit(int limit) {
        return limit < 1 ? 1 : Math.min(limit, MAX_INBOX_PAGE_LIMIT);
    }

    private static String encodeCursor(InboxConversationRow row) {
        long sortSeq = Objects.requireNonNullElse(row.lastMessageSeq(), 0L);
        String value = "v2|" + (row.pinned() ? "1" : "0") + "|" + sortSeq + "|"
                + row.createdAt() + "|" + row.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String token) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] fields = value.split("\\|", -1);
            if (fields.length == 4 && "v1".equals(fields[0])) {
                // Legacy cursor from before pins existed: it can only have
                // pointed into the unpinned part of the activity order.
                long seq = Long.parseLong(fields[1]);
                Instant createdAt = Instant.parse(fields[2]);
                UUID conversationId = UUID.fromString(fields[3]);
                if (seq < 0) {
                    throw new IllegalArgumentException("invalid cursor sequence");
                }
                return new Cursor(false, seq, createdAt, conversationId);
            }
            if (fields.length != 5 || !"v2".equals(fields[0])
                    || !("0".equals(fields[1]) || "1".equals(fields[1]))) {
                throw new IllegalArgumentException("invalid cursor version");
            }
            boolean pinned = "1".equals(fields[1]);
            long seq = Long.parseLong(fields[2]);
            Instant createdAt = Instant.parse(fields[3]);
            UUID conversationId = UUID.fromString(fields[4]);
            if (seq < 0) {
                throw new IllegalArgumentException("invalid cursor sequence");
            }
            return new Cursor(pinned, seq, createdAt, conversationId);
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
