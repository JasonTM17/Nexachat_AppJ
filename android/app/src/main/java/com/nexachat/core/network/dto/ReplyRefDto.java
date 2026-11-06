package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ReplyRefDto {
    @Nullable private final String id;
    @Nullable private final String senderId;
    @Nullable private final String senderName;
    @Nullable private final String senderDisplayName;
    @Nullable private final String snippet;
    @Nullable private final String content;

    public ReplyRefDto() {
        this(null, null, null, null, null, null);
    }

    public ReplyRefDto(@Nullable String id, @Nullable String senderId, @Nullable String senderName, @Nullable String senderDisplayName, @Nullable String snippet, @Nullable String content) {
        this.id = id;
        this.senderId = senderId;
        this.senderName = senderName;
        this.senderDisplayName = senderDisplayName;
        this.snippet = snippet;
        this.content = content;
    }

    public ReplyRefDto(String id, String senderName, String snippet) {
        this(id, null, senderName, null, snippet, null);
    }

    @Nullable public String getId() {
        return id;
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

    @Nullable public String getSnippet() {
        return snippet;
    }

    @Nullable public String getContent() {
        return content;
    }


    @Nullable
    public String getResolvedSenderName() {
        return senderName != null ? senderName : senderDisplayName;
    }

    @Nullable
    public String getResolvedSnippet() {
        return snippet != null ? snippet : content;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ReplyRefDto that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && Objects.equals(senderId, that.senderId)
                && Objects.equals(senderName, that.senderName)
                && Objects.equals(senderDisplayName, that.senderDisplayName)
                && Objects.equals(snippet, that.snippet)
                && Objects.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, senderId, senderName, senderDisplayName, snippet, content);
    }
}
