package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Inbox row; direct conversations carry a resolved peer and groups use title. */
@Entity(tableName = "conversations")
public final class ConversationEntity {
    @PrimaryKey @NonNull private final String id;
    @NonNull private final String type;
    @Nullable private final String title;
    private final long lastMessageSeq;
    @Nullable private final String lastMessageContent;
    @Nullable private final String lastMessageSenderName;
    /** Epoch milliseconds; null while the conversation has no messages. */
    @Nullable private final Long lastMessageAt;
    private final int unreadCount;
    @Nullable private final String otherUserName;
    @Nullable private final String otherUserAvatar;
    /** Group avatar URL set by an admin; direct rows leave it null. */
    @Nullable private final String avatarUrl;
    /** Client-only highest other-member read watermark. */
    private final long peerReadSeq;
    /** Client-only member role, null for direct conversations and absent payload fields. */
    @Nullable private final String myRole;
    /** Peer user id for direct rows, used to resolve presence. */
    @Nullable private final String otherUserId;
    /** True if the last message is soft-deleted and the snippet must be neutral. */
    private final boolean lastMessageDeleted;
    /** Client-only highest other-member delivered watermark. */
    private final long peerDeliveredSeq;
    /** Caller's own mute preference as returned by the server. */
    private final boolean muted;
    /** Epoch milliseconds of the caller's timed mute; null when forever or unmuted. */
    @Nullable private final Long mutedUntilEpochMs;
    /** Caller's own archive preference; archived rows stay out of the default inbox. */
    private final boolean archived;
    /** Caller's own per-conversation AI opt-out flag. */
    private final boolean aiOptOut;
    /** True if conversation is pinned by the caller; pinned rows sort first. */
    private final boolean pinned;
    /** Epoch milliseconds when pinned; null while not pinned. */
    @Nullable private final Long pinnedAt;
    /** Caller-owned label ids cached with the Room-backed inbox row. */
    @NonNull @ColumnInfo(defaultValue = "''") private final String labelIdsCsv;

    @androidx.room.Ignore
    public ConversationEntity(
            @NonNull String id,
            @NonNull String type,
            @Nullable String title,
            long lastMessageSeq,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable Long lastMessageAt,
            int unreadCount,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            long peerReadSeq,
            @Nullable String myRole,
            @Nullable String otherUserId,
            boolean lastMessageDeleted,
            long peerDeliveredSeq,
            boolean muted,
            @Nullable Long mutedUntilEpochMs,
            boolean archived,
            boolean aiOptOut,
            boolean pinned,
            @Nullable Long pinnedAt) {
        this(id, type, title, lastMessageSeq, lastMessageContent, lastMessageSenderName,
                lastMessageAt, unreadCount, otherUserName, otherUserAvatar, avatarUrl,
                peerReadSeq, myRole, otherUserId, lastMessageDeleted, peerDeliveredSeq,
                muted, mutedUntilEpochMs, archived, aiOptOut, pinned, pinnedAt, "");
    }

    public ConversationEntity(
            @NonNull String id,
            @NonNull String type,
            @Nullable String title,
            long lastMessageSeq,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable Long lastMessageAt,
            int unreadCount,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            long peerReadSeq,
            @Nullable String myRole,
            @Nullable String otherUserId,
            boolean lastMessageDeleted,
            long peerDeliveredSeq,
            boolean muted,
            @Nullable Long mutedUntilEpochMs,
            boolean archived,
            boolean aiOptOut,
            boolean pinned,
            @Nullable Long pinnedAt,
            @NonNull String labelIdsCsv) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.lastMessageSeq = lastMessageSeq;
        this.lastMessageContent = lastMessageContent;
        this.lastMessageSenderName = lastMessageSenderName;
        this.lastMessageAt = lastMessageAt;
        this.unreadCount = unreadCount;
        this.otherUserName = otherUserName;
        this.otherUserAvatar = otherUserAvatar;
        this.avatarUrl = avatarUrl;
        this.peerReadSeq = peerReadSeq;
        this.myRole = myRole;
        this.otherUserId = otherUserId;
        this.lastMessageDeleted = lastMessageDeleted;
        this.peerDeliveredSeq = peerDeliveredSeq;
        this.muted = muted;
        this.mutedUntilEpochMs = mutedUntilEpochMs;
        this.archived = archived;
        this.aiOptOut = aiOptOut;
        this.pinned = pinned;
        this.pinnedAt = pinnedAt;
        this.labelIdsCsv = Objects.requireNonNull(labelIdsCsv, "labelIdsCsv");
    }

    @androidx.room.Ignore
    public ConversationEntity(
            @NonNull String id,
            @NonNull String type,
            @Nullable String title,
            long lastMessageSeq,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable Long lastMessageAt,
            int unreadCount,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            long peerReadSeq,
            @Nullable String myRole,
            @Nullable String otherUserId,
            boolean lastMessageDeleted,
            long peerDeliveredSeq,
            @NonNull String labelIdsCsv) {
        this(id, type, title, lastMessageSeq, lastMessageContent, lastMessageSenderName,
                lastMessageAt, unreadCount, otherUserName, otherUserAvatar, avatarUrl,
                peerReadSeq, myRole, otherUserId, lastMessageDeleted, peerDeliveredSeq,
                false, null, false, false, false, null, labelIdsCsv);
    }

    @androidx.room.Ignore
    public ConversationEntity(
            @NonNull String id,
            @NonNull String type,
            @Nullable String title,
            long lastMessageSeq,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable Long lastMessageAt,
            int unreadCount,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            long peerReadSeq,
            @Nullable String myRole,
            @Nullable String otherUserId,
            boolean lastMessageDeleted,
            long peerDeliveredSeq,
            boolean muted,
            @Nullable Long mutedUntilEpochMs,
            boolean archived,
            boolean aiOptOut) {
        this(id, type, title, lastMessageSeq, lastMessageContent, lastMessageSenderName,
                lastMessageAt, unreadCount, otherUserName, otherUserAvatar, avatarUrl,
                peerReadSeq, myRole, otherUserId, lastMessageDeleted, peerDeliveredSeq,
                muted, mutedUntilEpochMs, archived, aiOptOut, false, null);
    }

    @androidx.room.Ignore
    public ConversationEntity(
            @NonNull String id,
            @NonNull String type,
            @Nullable String title,
            long lastMessageSeq,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable Long lastMessageAt,
            int unreadCount,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            long peerReadSeq,
            @Nullable String myRole,
            @Nullable String otherUserId,
            boolean lastMessageDeleted,
            long peerDeliveredSeq,
            boolean pinned,
            @Nullable Long pinnedAt) {
        this(id, type, title, lastMessageSeq, lastMessageContent, lastMessageSenderName,
                lastMessageAt, unreadCount, otherUserName, otherUserAvatar, avatarUrl,
                peerReadSeq, myRole, otherUserId, lastMessageDeleted, peerDeliveredSeq,
                false, null, false, false, pinned, pinnedAt);
    }

    @androidx.room.Ignore
    public ConversationEntity(
            @NonNull String id,
            @NonNull String type,
            @Nullable String title,
            long lastMessageSeq,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable Long lastMessageAt,
            int unreadCount,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            long peerReadSeq,
            @Nullable String myRole,
            @Nullable String otherUserId,
            boolean lastMessageDeleted,
            long peerDeliveredSeq) {
        this(id, type, title, lastMessageSeq, lastMessageContent, lastMessageSenderName,
                lastMessageAt, unreadCount, otherUserName, otherUserAvatar, avatarUrl,
                peerReadSeq, myRole, otherUserId, lastMessageDeleted, peerDeliveredSeq,
                false, null, false, false, false, null);
    }

    @androidx.room.Ignore
    public ConversationEntity(
            @NonNull String id,
            @NonNull String type,
            @Nullable String title,
            long lastMessageSeq,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable Long lastMessageAt,
            int unreadCount,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            long peerReadSeq,
            @Nullable String myRole,
            @Nullable String otherUserId,
            boolean lastMessageDeleted) {
        this(id, type, title, lastMessageSeq, lastMessageContent, lastMessageSenderName,
                lastMessageAt, unreadCount, otherUserName, otherUserAvatar, avatarUrl,
                peerReadSeq, myRole, otherUserId, lastMessageDeleted, 0L,
                false, null, false, false, false, null);
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getType() { return type; }
    @Nullable public String getTitle() { return title; }
    public long getLastMessageSeq() { return lastMessageSeq; }
    @Nullable public String getLastMessageContent() { return lastMessageContent; }
    @Nullable public String getLastMessageSenderName() { return lastMessageSenderName; }
    @Nullable public Long getLastMessageAt() { return lastMessageAt; }
    public int getUnreadCount() { return unreadCount; }
    @Nullable public String getOtherUserName() { return otherUserName; }
    @Nullable public String getOtherUserAvatar() { return otherUserAvatar; }
    @Nullable public String getAvatarUrl() { return avatarUrl; }
    public long getPeerReadSeq() { return peerReadSeq; }
    @Nullable public String getMyRole() { return myRole; }
    @Nullable public String getOtherUserId() { return otherUserId; }
    public boolean getLastMessageDeleted() { return lastMessageDeleted; }
    public long getPeerDeliveredSeq() { return peerDeliveredSeq; }
    public boolean isMuted() { return muted; }
    @Nullable public Long getMutedUntilEpochMs() { return mutedUntilEpochMs; }
    public boolean isArchived() { return archived; }
    public boolean isAiOptOut() { return aiOptOut; }
    public boolean isPinned() { return pinned; }
    @Nullable public Long getPinnedAt() { return pinnedAt; }
    @NonNull public String getLabelIdsCsv() { return labelIdsCsv; }
    @NonNull public List<String> getLabelIds() {
        if (labelIdsCsv.isBlank()) return List.of();
        return Arrays.stream(labelIdsCsv.split(","))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    /** Returns this row with a locally renamed title. */
    @NonNull public ConversationEntity withTitle(@Nullable String newTitle) {
        return new ConversationEntity(id, type, newTitle, lastMessageSeq, lastMessageContent,
                lastMessageSenderName, lastMessageAt, unreadCount, otherUserName,
                otherUserAvatar, avatarUrl, peerReadSeq, myRole, otherUserId, lastMessageDeleted,
                peerDeliveredSeq, muted, mutedUntilEpochMs, archived, aiOptOut, pinned, pinnedAt,
                labelIdsCsv);
    }

    /** Returns this row with its group avatar URL replaced. */
    @NonNull public ConversationEntity withAvatarUrl(@Nullable String newAvatarUrl) {
        return new ConversationEntity(id, type, title, lastMessageSeq, lastMessageContent,
                lastMessageSenderName, lastMessageAt, unreadCount, otherUserName,
                otherUserAvatar, newAvatarUrl, peerReadSeq, myRole, otherUserId,
                lastMessageDeleted, peerDeliveredSeq, muted, mutedUntilEpochMs, archived, aiOptOut,
                pinned, pinnedAt, labelIdsCsv);
    }

    /** Returns this row with client-only values merged from local state. */
    @NonNull public ConversationEntity withClientColumns(
            long newPeerReadSeq, @Nullable String newMyRole, @Nullable String newOtherUserId) {
        return new ConversationEntity(id, type, title, lastMessageSeq, lastMessageContent,
                lastMessageSenderName, lastMessageAt, unreadCount, otherUserName,
                otherUserAvatar, avatarUrl, newPeerReadSeq, newMyRole, newOtherUserId,
                lastMessageDeleted, peerDeliveredSeq, muted, mutedUntilEpochMs, archived, aiOptOut,
                pinned, pinnedAt, labelIdsCsv);
    }

    /** Returns this row with updated peerDeliveredSeq. */
    @NonNull public ConversationEntity withPeerDeliveredSeq(long newPeerDeliveredSeq) {
        return new ConversationEntity(id, type, title, lastMessageSeq, lastMessageContent,
                lastMessageSenderName, lastMessageAt, unreadCount, otherUserName,
                otherUserAvatar, avatarUrl, peerReadSeq, myRole, otherUserId,
                lastMessageDeleted, newPeerDeliveredSeq, muted, mutedUntilEpochMs, archived, aiOptOut,
                pinned, pinnedAt, labelIdsCsv);
    }

    /** Returns this row with updated pinned state. */
    @NonNull public ConversationEntity withPinned(boolean newPinned, @Nullable Long newPinnedAt) {
        return new ConversationEntity(id, type, title, lastMessageSeq, lastMessageContent,
                lastMessageSenderName, lastMessageAt, unreadCount, otherUserName,
                otherUserAvatar, avatarUrl, peerReadSeq, myRole, otherUserId,
                lastMessageDeleted, peerDeliveredSeq, muted, mutedUntilEpochMs, archived, aiOptOut,
                newPinned, newPinnedAt, labelIdsCsv);
    }

    /** Returns this row with a replaced caller-owned label assignment cache. */
    @NonNull public ConversationEntity withLabelIdsCsv(@NonNull String newLabelIdsCsv) {
        return new ConversationEntity(id, type, title, lastMessageSeq, lastMessageContent,
                lastMessageSenderName, lastMessageAt, unreadCount, otherUserName,
                otherUserAvatar, avatarUrl, peerReadSeq, myRole, otherUserId, lastMessageDeleted,
                peerDeliveredSeq, muted, mutedUntilEpochMs, archived, aiOptOut, pinned, pinnedAt,
                newLabelIdsCsv);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ConversationEntity that)) return false;
        return lastMessageSeq == that.lastMessageSeq && unreadCount == that.unreadCount
                && peerReadSeq == that.peerReadSeq && lastMessageDeleted == that.lastMessageDeleted
                && peerDeliveredSeq == that.peerDeliveredSeq
                && muted == that.muted && archived == that.archived
                && aiOptOut == that.aiOptOut
                && pinned == that.pinned
                && Objects.equals(pinnedAt, that.pinnedAt)
                && Objects.equals(id, that.id) && Objects.equals(type, that.type)
                && Objects.equals(title, that.title)
                && Objects.equals(lastMessageContent, that.lastMessageContent)
                && Objects.equals(lastMessageSenderName, that.lastMessageSenderName)
                && Objects.equals(lastMessageAt, that.lastMessageAt)
                && Objects.equals(otherUserName, that.otherUserName)
                && Objects.equals(otherUserAvatar, that.otherUserAvatar)
                && Objects.equals(avatarUrl, that.avatarUrl) && Objects.equals(myRole, that.myRole)
                && Objects.equals(otherUserId, that.otherUserId)
                && Objects.equals(mutedUntilEpochMs, that.mutedUntilEpochMs)
                && Objects.equals(labelIdsCsv, that.labelIdsCsv);
    }

    @Override public int hashCode() {
        return Objects.hash(id, type, title, lastMessageSeq, lastMessageContent,
                lastMessageSenderName, lastMessageAt, unreadCount, otherUserName,
                otherUserAvatar, avatarUrl, peerReadSeq, myRole, otherUserId,
                lastMessageDeleted, peerDeliveredSeq, muted, mutedUntilEpochMs, archived, aiOptOut,
                pinned, pinnedAt, labelIdsCsv);
    }

    @NonNull @Override public String toString() {
        return "ConversationEntity(id=" + id + ", type=" + type + ", title=" + title
                + ", lastMessageSeq=" + lastMessageSeq + ", lastMessageContent="
                + lastMessageContent + ", lastMessageSenderName=" + lastMessageSenderName
                + ", lastMessageAt=" + lastMessageAt + ", unreadCount=" + unreadCount
                + ", otherUserName=" + otherUserName + ", otherUserAvatar=" + otherUserAvatar
                + ", avatarUrl=" + avatarUrl + ", peerReadSeq=" + peerReadSeq + ", myRole="
                + myRole + ", otherUserId=" + otherUserId + ", lastMessageDeleted="
                + lastMessageDeleted + ", peerDeliveredSeq=" + peerDeliveredSeq
                + ", muted=" + muted + ", mutedUntilEpochMs=" + mutedUntilEpochMs
                + ", archived=" + archived + ", aiOptOut=" + aiOptOut
                + ", pinned=" + pinned + ", pinnedAt=" + pinnedAt
                + ", labelIdsCsv=" + labelIdsCsv + ')';
    }
}
