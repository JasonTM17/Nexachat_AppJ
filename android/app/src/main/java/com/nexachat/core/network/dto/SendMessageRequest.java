package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class SendMessageRequest {
    private final String clientMessageId;
    private final String type;
    @Nullable private final String content;
    @Nullable private final String replyToId;
    @Nullable private final List<String> attachmentIds;

    public SendMessageRequest() {
        this(null, MessageDto.TYPE_TEXT, null, null, List.of());
    }

    public SendMessageRequest(@NonNull String clientMessageId, @NonNull String type, @Nullable String content, @Nullable String replyToId, @Nullable List<String> attachmentIds) {
        this.clientMessageId = clientMessageId;
        this.type = type;
        this.content = content;
        this.replyToId = replyToId;
        this.attachmentIds = attachmentIds == null ? List.of() : attachmentIds;
    }

    @NonNull public String getClientMessageId() {
        return clientMessageId;
    }

    @NonNull public String getType() {
        return type;
    }

    @Nullable public String getContent() {
        return content;
    }

    @Nullable public String getReplyToId() {
        return replyToId;
    }

    @NonNull public List<String> getAttachmentIds() {
        return attachmentIds;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SendMessageRequest that)) {
            return false;
        }
        return Objects.equals(clientMessageId, that.clientMessageId)
                && Objects.equals(type, that.type)
                && Objects.equals(content, that.content)
                && Objects.equals(replyToId, that.replyToId)
                && Objects.equals(attachmentIds, that.attachmentIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientMessageId, type, content, replyToId, attachmentIds);
    }
}
