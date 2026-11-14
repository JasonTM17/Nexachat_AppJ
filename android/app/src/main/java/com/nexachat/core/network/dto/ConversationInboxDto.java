package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ConversationInboxDto {
    private final String id;
    @Nullable private final String type;
    @Nullable private final String title;
    @Nullable private final Long lastMessageSeq;
    @Nullable private final InboxLastMessageDto lastMessage;
    @Nullable private final String lastMessageContent;
    @Nullable private final String lastMessageSenderName;
    @Nullable private final String lastMessageAt;
    @Nullable private final Integer unreadCount;
    @Nullable private final Long lastReadSeq;
    @Nullable private final OtherUserDto otherUser;
    @Nullable private final String otherUserName;
    @Nullable private final String otherUserAvatar;
    @Nullable private final String avatarUrl;
    @Nullable private final List<ConversationMemberDto> members;
    @Nullable private final PeerPresenceDto peerPresence;
    @Nullable private final Boolean muted;
    @Nullable private final String mutedUntil;
    @Nullable private final Boolean archived;
    @Nullable private final Boolean aiOptOut;
    @Nullable private final Boolean pinned;
    @Nullable private final String pinnedAt;
    @Nullable private final List<String> labels;
    @Nullable private final Boolean e2eeEnabled;
    @Nullable private final Boolean lastMessageEncrypted;

    public ConversationInboxDto() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null, List.of(), null,
                null, null, null, null, null, null);
    }

    public ConversationInboxDto(
            @NonNull String id,
            @Nullable String type,
            @Nullable String title,
            @Nullable Long lastMessageSeq,
            @Nullable InboxLastMessageDto lastMessage,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable String lastMessageAt,
            @Nullable Integer unreadCount,
            @Nullable Long lastReadSeq,
            @Nullable OtherUserDto otherUser,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            @Nullable List<ConversationMemberDto> members,
            @Nullable PeerPresenceDto peerPresence,
            @Nullable Boolean muted,
            @Nullable String mutedUntil,
            @Nullable Boolean archived,
            @Nullable Boolean aiOptOut,
            @Nullable Boolean pinned,
            @Nullable String pinnedAt) {
        this(id, type, title, lastMessageSeq, lastMessage, lastMessageContent,
                lastMessageSenderName, lastMessageAt, unreadCount, lastReadSeq, otherUser,
                otherUserName, otherUserAvatar, avatarUrl, members, peerPresence, muted,
                mutedUntil, archived, aiOptOut, pinned, pinnedAt, List.of(), null, null);
    }

    public ConversationInboxDto(
            @NonNull String id,
            @Nullable String type,
            @Nullable String title,
            @Nullable Long lastMessageSeq,
            @Nullable InboxLastMessageDto lastMessage,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable String lastMessageAt,
            @Nullable Integer unreadCount,
            @Nullable Long lastReadSeq,
            @Nullable OtherUserDto otherUser,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            @Nullable List<ConversationMemberDto> members,
            @Nullable PeerPresenceDto peerPresence,
            @Nullable Boolean muted,
            @Nullable String mutedUntil,
            @Nullable Boolean archived,
            @Nullable Boolean aiOptOut,
            @Nullable Boolean pinned,
            @Nullable String pinnedAt,
            @Nullable List<String> labels) {
        this(id, type, title, lastMessageSeq, lastMessage, lastMessageContent, lastMessageSenderName,
                lastMessageAt, unreadCount, lastReadSeq, otherUser, otherUserName, otherUserAvatar,
                avatarUrl, members, peerPresence, muted, mutedUntil, archived, aiOptOut, pinned,
                pinnedAt, labels, null, null);
    }

    public ConversationInboxDto(
            @NonNull String id,
            @Nullable String type,
            @Nullable String title,
            @Nullable Long lastMessageSeq,
            @Nullable InboxLastMessageDto lastMessage,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable String lastMessageAt,
            @Nullable Integer unreadCount,
            @Nullable Long lastReadSeq,
            @Nullable OtherUserDto otherUser,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            @Nullable List<ConversationMemberDto> members,
            @Nullable PeerPresenceDto peerPresence,
            @Nullable Boolean muted,
            @Nullable String mutedUntil,
            @Nullable Boolean archived,
            @Nullable Boolean aiOptOut,
            @Nullable Boolean pinned,
            @Nullable String pinnedAt,
            @Nullable List<String> labels,
            @Nullable Boolean e2eeEnabled,
            @Nullable Boolean lastMessageEncrypted) {
        this.e2eeEnabled = e2eeEnabled;
        this.lastMessageEncrypted = lastMessageEncrypted;
        this.id = id;
        this.type = type;
        this.title = title;
        this.lastMessageSeq = lastMessageSeq;
        this.lastMessage = lastMessage;
        this.lastMessageContent = lastMessageContent;
        this.lastMessageSenderName = lastMessageSenderName;
        this.lastMessageAt = lastMessageAt;
        this.unreadCount = unreadCount;
        this.lastReadSeq = lastReadSeq;
        this.otherUser = otherUser;
        this.otherUserName = otherUserName;
        this.otherUserAvatar = otherUserAvatar;
        this.avatarUrl = avatarUrl;
        this.members = members == null ? List.of() : members;
        this.peerPresence = peerPresence;
        this.muted = muted;
        this.mutedUntil = mutedUntil;
        this.archived = archived;
        this.aiOptOut = aiOptOut;
        this.pinned = pinned;
        this.pinnedAt = pinnedAt;
        this.labels = labels == null ? List.of() : List.copyOf(labels);
    }

    /** Inbox variant that carries caller-owned labels without preference fields. */
    public ConversationInboxDto(
            @NonNull String id,
            @Nullable String type,
            @Nullable String title,
            @Nullable Long lastMessageSeq,
            @Nullable InboxLastMessageDto lastMessage,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable String lastMessageAt,
            @Nullable Integer unreadCount,
            @Nullable Long lastReadSeq,
            @Nullable OtherUserDto otherUser,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            @Nullable List<ConversationMemberDto> members,
            @Nullable PeerPresenceDto peerPresence,
            @Nullable List<String> labels) {
        this(id, type, title, lastMessageSeq, lastMessage, lastMessageContent, lastMessageSenderName,
                lastMessageAt, unreadCount, lastReadSeq, otherUser, otherUserName, otherUserAvatar,
                avatarUrl, members, peerPresence, null, null, null, null, null, null, labels);
    }

    public ConversationInboxDto(
            @NonNull String id,
            @Nullable String type,
            @Nullable String title,
            @Nullable Long lastMessageSeq,
            @Nullable InboxLastMessageDto lastMessage,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable String lastMessageAt,
            @Nullable Integer unreadCount,
            @Nullable Long lastReadSeq,
            @Nullable OtherUserDto otherUser,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            @Nullable List<ConversationMemberDto> members,
            @Nullable PeerPresenceDto peerPresence,
            @Nullable Boolean muted,
            @Nullable String mutedUntil,
            @Nullable Boolean archived,
            @Nullable Boolean aiOptOut) {
        this(id, type, title, lastMessageSeq, lastMessage, lastMessageContent, lastMessageSenderName,
                lastMessageAt, unreadCount, lastReadSeq, otherUser, otherUserName, otherUserAvatar,
                avatarUrl, members, peerPresence, muted, mutedUntil, archived, aiOptOut, null, null);
    }

    public ConversationInboxDto(
            @NonNull String id,
            @Nullable String type,
            @Nullable String title,
            @Nullable Long lastMessageSeq,
            @Nullable InboxLastMessageDto lastMessage,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable String lastMessageAt,
            @Nullable Integer unreadCount,
            @Nullable Long lastReadSeq,
            @Nullable OtherUserDto otherUser,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            @Nullable List<ConversationMemberDto> members,
            @Nullable PeerPresenceDto peerPresence,
            @Nullable Boolean pinned,
            @Nullable String pinnedAt) {
        this(id, type, title, lastMessageSeq, lastMessage, lastMessageContent, lastMessageSenderName,
                lastMessageAt, unreadCount, lastReadSeq, otherUser, otherUserName, otherUserAvatar,
                avatarUrl, members, peerPresence, null, null, null, null, pinned, pinnedAt);
    }

    public ConversationInboxDto(String id, String type, String title, java.util.List<ConversationMemberDto> members) {
        this(id, type, title, null, null, null, null, null, null, null, null, null, null, null, members, null,
                null, null, null, null, null, null);
    }

    /** Legacy fixture shape without the caller-scoped preference flags. */
    public ConversationInboxDto(
            @NonNull String id,
            @Nullable String type,
            @Nullable String title,
            @Nullable Long lastMessageSeq,
            @Nullable InboxLastMessageDto lastMessage,
            @Nullable String lastMessageContent,
            @Nullable String lastMessageSenderName,
            @Nullable String lastMessageAt,
            @Nullable Integer unreadCount,
            @Nullable Long lastReadSeq,
            @Nullable OtherUserDto otherUser,
            @Nullable String otherUserName,
            @Nullable String otherUserAvatar,
            @Nullable String avatarUrl,
            @Nullable List<ConversationMemberDto> members,
            @Nullable PeerPresenceDto peerPresence) {
        this(id, type, title, lastMessageSeq, lastMessage, lastMessageContent, lastMessageSenderName,
                lastMessageAt, unreadCount, lastReadSeq, otherUser, otherUserName, otherUserAvatar,
                avatarUrl, members, peerPresence, null, null, null, null, null, null);
    }

    @NonNull public String getId() {
        return id;
    }

    @Nullable public String getType() {
        return type;
    }

    @Nullable public String getTitle() {
        return title;
    }

    @Nullable public Long getLastMessageSeq() {
        return lastMessageSeq;
    }

    @Nullable public InboxLastMessageDto getLastMessage() {
        return lastMessage;
    }

    @Nullable public String getLastMessageContent() {
        return lastMessageContent;
    }

    @Nullable public String getLastMessageSenderName() {
        return lastMessageSenderName;
    }

    @Nullable public String getLastMessageAt() {
        return lastMessageAt;
    }

    @Nullable public Integer getUnreadCount() {
        return unreadCount;
    }

    @Nullable public Long getLastReadSeq() {
        return lastReadSeq;
    }

    @Nullable public OtherUserDto getOtherUser() {
        return otherUser;
    }

    @Nullable public String getOtherUserName() {
        return otherUserName;
    }

    @Nullable public String getOtherUserAvatar() {
        return otherUserAvatar;
    }

    @Nullable public String getAvatarUrl() {
        return avatarUrl;
    }

    @NonNull public List<ConversationMemberDto> getMembers() {
        return members;
    }

    @Nullable public PeerPresenceDto getPeerPresence() {
        return peerPresence;
    }

    /** Caller's own mute flag; absent in legacy payloads. */
    @Nullable public Boolean getMuted() {
        return muted;
    }

    /** Caller's mute expiry as ISO-8601; null when forever or unmuted. */
    @Nullable public String getMutedUntil() {
        return mutedUntil;
    }

    /** Caller's own archive flag; absent in legacy payloads. */
    @Nullable public Boolean getArchived() {
        return archived;
    }

    /** Caller's own per-conversation AI opt-out flag; absent in legacy payloads. */
    @Nullable public Boolean getAiOptOut() {
        return aiOptOut;
    }

    @Nullable public Boolean getPinned() {
        return pinned;
    }

    public boolean isPinned() {
        return Boolean.TRUE.equals(pinned);
    }

    @Nullable public String getPinnedAt() {
        return pinnedAt;
    }

    @NonNull public List<String> getLabels() {
        return labels == null ? List.of() : labels;
    }

    /** Server E2EE flag on the conversation; absent in legacy payloads. */
    @Nullable public Boolean getE2eeEnabled() {
        return e2eeEnabled;
    }

    /** True when the last-message preview is a ciphertext the server did not read. */
    @Nullable public Boolean getLastMessageEncrypted() {
        return lastMessageEncrypted;
    }

    @Nullable
    public String resolvedLastContent() {
        if (lastMessage != null && lastMessage.getDeletedAt() != null) {
            return null;
        }
        if (lastMessage != null && lastMessage.getContent() != null) {
            return lastMessage.getContent();
        }
        if (lastMessage != null && lastMessage.getContentSnippet() != null) {
            return lastMessage.getContentSnippet();
        }
        if (lastMessageContent != null) {
            return lastMessageContent;
        }
        return lastMessage == null ? null : lastMessage.getType();
    }

    @Nullable
    public String resolvedLastSender() {
        if (lastMessage != null && lastMessage.getSenderDisplayName() != null) {
            return lastMessage.getSenderDisplayName();
        }
        if (lastMessage != null && lastMessage.getSenderName() != null) {
            return lastMessage.getSenderName();
        }
        return lastMessageSenderName;
    }

    @Nullable
    public String resolvedLastAt() {
        if (lastMessage != null && lastMessage.getCreatedAt() != null) {
            return lastMessage.getCreatedAt();
        }
        return lastMessageAt;
    }

    @Nullable
    public Long resolvedLastSeq() {
        if (lastMessage != null && lastMessage.getSeq() != null) {
            return lastMessage.getSeq();
        }
        return lastMessageSeq;
    }

    /** The server's last-message type ("TEXT", "SYSTEM", …); null on legacy flat payloads. */
    @Nullable
    public String resolvedLastType() {
        return lastMessage == null ? null : lastMessage.getType();
    }

    @Nullable
    public String resolvedOtherName(@Nullable String myUserId) {
        if (otherUser != null && otherUser.getDisplayName() != null) {
            return otherUser.getDisplayName();
        }
        if (otherUserName != null) {
            return otherUserName;
        }
        ConversationMemberDto member = firstOther(myUserId);
        return member == null ? null : member.getDisplayName();
    }

    @Nullable
    public String resolvedOtherId(@Nullable String myUserId) {
        if (otherUser != null && otherUser.getUserId() != null) {
            return otherUser.getUserId();
        }
        ConversationMemberDto member = firstOther(myUserId);
        return member == null ? null : member.getUserId();
    }

    @Nullable
    public String resolvedOtherAvatar(@Nullable String myUserId) {
        if (otherUser != null && otherUser.getAvatarUrl() != null) {
            return otherUser.getAvatarUrl();
        }
        if (otherUserAvatar != null) {
            return otherUserAvatar;
        }
        if (type != null && !"DIRECT".equals(type)) {
            return null;
        }
        ConversationMemberDto member = firstOther(myUserId);
        return member == null ? null : member.getAvatarUrl();
    }

    @Nullable
    private ConversationMemberDto firstOther(@Nullable String myUserId) {
        for (ConversationMemberDto member : members) {
            String memberId = member.getUserId();
            if (memberId != null && !memberId.equals(myUserId)) {
                return member;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ConversationInboxDto that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && Objects.equals(type, that.type)
                && Objects.equals(title, that.title)
                && Objects.equals(lastMessageSeq, that.lastMessageSeq)
                && Objects.equals(lastMessage, that.lastMessage)
                && Objects.equals(lastMessageContent, that.lastMessageContent)
                && Objects.equals(lastMessageSenderName, that.lastMessageSenderName)
                && Objects.equals(lastMessageAt, that.lastMessageAt)
                && Objects.equals(unreadCount, that.unreadCount)
                && Objects.equals(lastReadSeq, that.lastReadSeq)
                && Objects.equals(otherUser, that.otherUser)
                && Objects.equals(otherUserName, that.otherUserName)
                && Objects.equals(otherUserAvatar, that.otherUserAvatar)
                && Objects.equals(avatarUrl, that.avatarUrl)
                && Objects.equals(members, that.members)
                && Objects.equals(peerPresence, that.peerPresence)
                && Objects.equals(muted, that.muted)
                && Objects.equals(mutedUntil, that.mutedUntil)
                && Objects.equals(archived, that.archived)
                && Objects.equals(aiOptOut, that.aiOptOut)
                && Objects.equals(pinned, that.pinned)
                && Objects.equals(pinnedAt, that.pinnedAt)
                && Objects.equals(labels, that.labels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, title, lastMessageSeq, lastMessage, lastMessageContent,
                lastMessageSenderName, lastMessageAt, unreadCount, lastReadSeq, otherUser,
                otherUserName, otherUserAvatar, avatarUrl, members, peerPresence,
                muted, mutedUntil, archived, aiOptOut, pinned, pinnedAt, labels);
    }
}
