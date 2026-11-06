package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class InboxLastMessageDto {
    @Nullable private final Long seq;
    @Nullable private final String type;
    @Nullable private final String content;
    @Nullable private final String contentSnippet;
    @Nullable private final String senderId;
    @Nullable private final String senderName;
    @Nullable private final String senderDisplayName;
    @Nullable private final String createdAt;
    @Nullable private final String deletedAt;

    public InboxLastMessageDto() {
        this(null, null, null, null, null, null, null, null, null);
    }

    public InboxLastMessageDto(@Nullable Long seq, @Nullable String type, @Nullable String content, @Nullable String contentSnippet, @Nullable String senderId, @Nullable String senderName, @Nullable String senderDisplayName, @Nullable String createdAt, @Nullable String deletedAt) {
        this.seq = seq;
        this.type = type;
        this.content = content;
        this.contentSnippet = contentSnippet;
        this.senderId = senderId;
        this.senderName = senderName;
        this.senderDisplayName = senderDisplayName;
        this.createdAt = createdAt;
        this.deletedAt = deletedAt;
    }

    @Nullable public Long getSeq() {
        return seq;
    }

    @Nullable public String getType() {
        return type;
    }

    @Nullable public String getContent() {
        return content;
    }

    @Nullable public String getContentSnippet() {
        return contentSnippet;
    }

    @Nullable public String getSenderId() {
        return senderId;
    }

    @Nullable public String getSenderName() {
        return senderName;
    }

    @Nullable public String getSenderDisplayName() {
        return senderDisplayName;
    }

    @Nullable public String getCreatedAt() {
        return createdAt;
    }

    @Nullable public String getDeletedAt() {
        return deletedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof InboxLastMessageDto that)) {
            return false;
        }
        return Objects.equals(seq, that.seq)
                && Objects.equals(type, that.type)
                && Objects.equals(content, that.content)
                && Objects.equals(contentSnippet, that.contentSnippet)
                && Objects.equals(senderId, that.senderId)
                && Objects.equals(senderName, that.senderName)
                && Objects.equals(senderDisplayName, that.senderDisplayName)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(deletedAt, that.deletedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seq, type, content, contentSnippet, senderId, senderName, senderDisplayName, createdAt, deletedAt);
    }
}
