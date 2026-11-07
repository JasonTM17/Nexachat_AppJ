package com.nexachat.data;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Inbox row. Group title wins over the peer name. */
public final class ConversationSummary {

    public static final String TYPE_GROUP = "GROUP";

    private final String id;
    private final String type;
    @Nullable private final String title;
    @Nullable private final String lastMessageContent;
    @Nullable private final String lastMessageSenderName;
    @Nullable private final Long lastMessageAtEpochMs;
    private final long lastMessageSeq;
    private final int unreadCount;
    @Nullable private final String otherUserName;
    @Nullable private final String otherUserAvatar;
    @Nullable private final String avatarUrl;
    private final long peerReadSeq;
    @Nullable private final String myRole;
    @Nullable private final String otherUserId;
    private final boolean deletedLastMessage;
    @Nullable private final PeerPresence peerPresence;

    public ConversationSummary(
            String id,
            String type,
            @Nullable String title,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable Long lastMessageAtEpochMs,
            long lastMessageSeq,
            int unreadCount,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl) {
        this(
                id,
                type,
                title,
                lastMessageContent,
                lastMessageSenderName,
                lastMessageAtEpochMs,
                lastMessageSeq,
                unreadCount,
                otherUserName,
                otherUserAvatar,
                avatarUrl,
                0L,
                null,
                null,
                false,
                null);
    }

    public ConversationSummary(
            String id,
            String type,
            @Nullable String title,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable Long lastMessageAtEpochMs,
            long lastMessageSeq,
            int unreadCount,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            long peerReadSeq,
            @Nullable String myRole,
            @Nullable String otherUserId,
            boolean lastMessageDeleted,
            @Nullable PeerPresence peerPresence) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.title = title;
        this.lastMessageContent = lastMessageContent;
        this.lastMessageSenderName = lastMessageSenderName;
        this.lastMessageAtEpochMs = lastMessageAtEpochMs;
        this.lastMessageSeq = lastMessageSeq;
        this.unreadCount = unreadCount;
        this.otherUserName = otherUserName;
        this.otherUserAvatar = otherUserAvatar;
        this.avatarUrl = avatarUrl;
        this.peerReadSeq = peerReadSeq;
        this.myRole = myRole;
        this.otherUserId = otherUserId;
        this.deletedLastMessage = lastMessageDeleted;
        this.peerPresence = peerPresence;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    @Nullable
    public String getTitle() {
        return title;
    }

    @Nullable
    public String getLastMessageContent() {
        return lastMessageContent;
    }

    @Nullable
    public String getLastMessageSenderName() {
        return lastMessageSenderName;
    }

    @Nullable
    public Long getLastMessageAtEpochMs() {
        return lastMessageAtEpochMs;
    }

    public long getLastMessageSeq() {
        return lastMessageSeq;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    @Nullable
    public String getOtherUserName() {
        return otherUserName;
    }

    @Nullable
    public String getOtherUserAvatar() {
        return otherUserAvatar;
    }

    @Nullable
    public String getAvatarUrl() {
        return avatarUrl;
    }

    public long getPeerReadSeq() {
        return peerReadSeq;
    }

    @Nullable
    public String getMyRole() {
        return myRole;
    }

    @Nullable
    public String getOtherUserId() {
        return otherUserId;
    }

    public boolean getLastMessageDeleted() {
        return deletedLastMessage;
    }

    @Nullable
    public PeerPresence getPeerPresence() {
        return peerPresence;
    }

    public String getDisplayName() {
        if (title != null) {
            return title;
        }
        return otherUserName != null ? otherUserName : "";
    }

    @Nullable
    public String getDisplayAvatar() {
        return avatarUrl != null ? avatarUrl : otherUserAvatar;
    }

    public boolean isGroup() {
        return TYPE_GROUP.equals(type);
    }

    public ConversationSummary withTitle(@Nullable String nextTitle) {
        return new ConversationSummary(
                id,
                type,
                nextTitle,
                lastMessageContent,
                lastMessageSenderName,
                lastMessageAtEpochMs,
                lastMessageSeq,
                unreadCount,
                otherUserName,
                otherUserAvatar,
                avatarUrl,
                peerReadSeq,
                myRole,
                otherUserId,
                deletedLastMessage,
                peerPresence);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ConversationSummary that)) {
            return false;
        }
        return lastMessageSeq == that.lastMessageSeq
                && unreadCount == that.unreadCount
                && peerReadSeq == that.peerReadSeq
                && deletedLastMessage == that.deletedLastMessage
                && id.equals(that.id)
                && type.equals(that.type)
                && Objects.equals(title, that.title)
                && Objects.equals(lastMessageContent, that.lastMessageContent)
                && Objects.equals(lastMessageSenderName, that.lastMessageSenderName)
                && Objects.equals(lastMessageAtEpochMs, that.lastMessageAtEpochMs)
                && Objects.equals(otherUserName, that.otherUserName)
                && Objects.equals(otherUserAvatar, that.otherUserAvatar)
                && Objects.equals(avatarUrl, that.avatarUrl)
                && Objects.equals(myRole, that.myRole)
                && Objects.equals(otherUserId, that.otherUserId)
                && Objects.equals(peerPresence, that.peerPresence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                id,
                type,
                title,
                lastMessageContent,
                lastMessageSenderName,
                lastMessageAtEpochMs,
                lastMessageSeq,
                unreadCount,
                otherUserName,
                otherUserAvatar,
                avatarUrl,
                peerReadSeq,
                myRole,
                otherUserId,
                deletedLastMessage,
                peerPresence);
    }
}
