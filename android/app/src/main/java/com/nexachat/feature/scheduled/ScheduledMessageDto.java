package com.nexachat.feature.scheduled;

import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/** Reflective Moshi bean for one scheduled message response. */
public final class ScheduledMessageDto {
    @Nullable private final String id;
    @Nullable private final String conversationId;
    @Nullable private final String clientMessageId;
    @Nullable private final String body;
    @Nullable private final String sendAt;
    @Nullable private final String status;
    @Nullable private final String failureCode;
    @Nullable private final String sentMessageId;
    @Nullable private final String createdAt;
    @Nullable private final String updatedAt;

    public ScheduledMessageDto() {
        this(null, null, null, null, null, null, null, null, null, null);
    }

    public ScheduledMessageDto(
            @Nullable String id,
            @Nullable String conversationId,
            @Nullable String clientMessageId,
            @Nullable String body,
            @Nullable String sendAt,
            @Nullable String status,
            @Nullable String failureCode,
            @Nullable String sentMessageId,
            @Nullable String createdAt,
            @Nullable String updatedAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.clientMessageId = clientMessageId;
        this.body = body;
        this.sendAt = sendAt;
        this.status = status;
        this.failureCode = failureCode;
        this.sentMessageId = sentMessageId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @Nullable public String getId() { return id; }
    @Nullable public String getConversationId() { return conversationId; }
    @Nullable public String getClientMessageId() { return clientMessageId; }
    @Nullable public String getBody() { return body; }
    @Nullable public String getSendAt() { return sendAt; }
    @Nullable public String getStatus() { return status; }
    @Nullable public String getFailureCode() { return failureCode; }
    @Nullable public String getSentMessageId() { return sentMessageId; }
    @Nullable public String getCreatedAt() { return createdAt; }
    @Nullable public String getUpdatedAt() { return updatedAt; }

    /**
     * Returns null when the server row is not renderable. The body is allowed to
     * be empty: the server clears it once the row reaches a terminal status.
     */
    @Nullable
    public ScheduledMessage toDomain() {
        if (isBlank(id) || isBlank(conversationId) || isBlank(clientMessageId)) {
            return null;
        }
        Instant send = ScheduledMessageTime.parse(sendAt);
        ScheduledMessageStatus parsed = ScheduledMessageStatus.fromWire(status);
        if (send == null || parsed == null) {
            return null;
        }
        return new ScheduledMessage(
                Objects.requireNonNull(id),
                Objects.requireNonNull(conversationId),
                Objects.requireNonNull(clientMessageId),
                body == null ? "" : body,
                send,
                parsed,
                isBlank(failureCode) ? null : failureCode.trim(),
                isBlank(sentMessageId) ? null : sentMessageId.trim(),
                ScheduledMessageTime.parse(createdAt),
                ScheduledMessageTime.parse(updatedAt));
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
