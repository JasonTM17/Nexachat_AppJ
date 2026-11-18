package com.nexachat.conversation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One row of {@code GET /conversations} — the inbox spec shape: members for
 * title/avatar derivation, denormalized {@code lastMessage} preview (no extra
 * query), the caller's unread count + watermark, and the caller's own
 * per-conversation AI opt-out state ({@code aiOptOut}).
 *
 * <p>{@code pinned}/{@code pinnedAt} are the caller's own per-member pin
 * state; {@code pinnedAt} is omitted while the conversation is not pinned.
 *
 * <p>{@code streakCount} is the chat's current streak (0 when there is none or
 * it has lapsed) and {@code streakAtRisk} is true while the streak is alive but
 * today's exchange is not complete yet.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InboxEntry(
        UUID id,
        String type,
        String title,
        String avatarUrl,
        List<Member> members,
        LastMessage lastMessage,
        long unreadCount,
        long lastReadSeq,
        PeerPresence peerPresence,
        boolean muted,
        Instant mutedUntil,
        boolean archived,
        boolean aiOptOut,
        boolean pinned,
        Instant pinnedAt,
        List<UUID> labels,
        boolean lastMessageEncrypted,
        boolean e2eeEnabled,
        int disappearingSeconds,
        int streakCount,
        boolean streakAtRisk
) {

    /** Pre-streak shape for callers that predate the chat streak fields. */
    public InboxEntry(UUID id, String type, String title, String avatarUrl,
                      List<Member> members, LastMessage lastMessage, long unreadCount,
                      long lastReadSeq, PeerPresence peerPresence, boolean muted,
                      Instant mutedUntil, boolean archived, boolean aiOptOut,
                      boolean pinned, Instant pinnedAt, List<UUID> labels,
                      boolean lastMessageEncrypted, boolean e2eeEnabled,
                      int disappearingSeconds) {
        this(id, type, title, avatarUrl, members, lastMessage, unreadCount, lastReadSeq,
                peerPresence, muted, mutedUntil, archived, aiOptOut, pinned, pinnedAt, labels,
                lastMessageEncrypted, e2eeEnabled, disappearingSeconds, 0, false);
    }

    /** Backward-compatible constructor for code that builds the pre-label inbox shape. */
    public InboxEntry(UUID id, String type, String title, String avatarUrl,
                      List<Member> members, LastMessage lastMessage, long unreadCount,
                      long lastReadSeq, PeerPresence peerPresence, boolean muted,
                      Instant mutedUntil, boolean archived, boolean aiOptOut,
                      boolean pinned, Instant pinnedAt) {
        this(id, type, title, avatarUrl, members, lastMessage, unreadCount, lastReadSeq,
                peerPresence, muted, mutedUntil, archived, aiOptOut, pinned, pinnedAt, List.of(),
                false, false, 0, 0, false);
    }

    /** Pre-disappearing shape for callers that predate the timer column. */
    public InboxEntry(UUID id, String type, String title, String avatarUrl,
                      List<Member> members, LastMessage lastMessage, long unreadCount,
                      long lastReadSeq, PeerPresence peerPresence, boolean muted,
                      Instant mutedUntil, boolean archived, boolean aiOptOut,
                      boolean pinned, Instant pinnedAt, List<UUID> labels,
                      boolean lastMessageEncrypted, boolean e2eeEnabled) {
        this(id, type, title, avatarUrl, members, lastMessage, unreadCount, lastReadSeq,
                peerPresence, muted, mutedUntil, archived, aiOptOut, pinned, pinnedAt, labels,
                lastMessageEncrypted, e2eeEnabled, 0, 0, false);
    }

    /** Member as the client needs it for headers/avatars plus admin gating. */
    public record Member(
            UUID userId,
            String displayName,
            String phone,
            String avatarUrl,
            String role
    ) {
    }

    /** Preview of the newest message; {@code null} for a conversation with no messages. */
    public record LastMessage(
            Long seq,
            String type,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String contentSnippet,
            UUID senderId,
            Instant createdAt,
            Instant deletedAt
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PeerPresence(boolean online, Instant lastSeenAt) {
    }
}
