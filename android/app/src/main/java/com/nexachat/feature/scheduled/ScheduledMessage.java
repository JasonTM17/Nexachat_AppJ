package com.nexachat.feature.scheduled;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/** One scheduled message of a conversation, as rendered by the scheduled list sheet. */
public final class ScheduledMessage {

    private final String id;
    private final String conversationId;
    private final String clientMessageId;
    private final String body;
    private final Instant sendAt;
    private final ScheduledMessageStatus status;
    @Nullable private final String failureCode;
    @Nullable private final String sentMessageId;
    @Nullable private final Instant createdAt;
    @Nullable private final Instant updatedAt;

    public ScheduledMessage(
            @NonNull String id,
            @NonNull String conversationId,
            @NonNull String clientMessageId,
            @NonNull String body,
            @NonNull Instant sendAt,
            @NonNull ScheduledMessageStatus status,
            @Nullable String failureCode,
            @Nullable String sentMessageId,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.clientMessageId = Objects.requireNonNull(clientMessageId, "clientMessageId");
        this.body = Objects.requireNonNull(body, "body");
        this.sendAt = Objects.requireNonNull(sendAt, "sendAt");
        this.status = Objects.requireNonNull(status, "status");
        this.failureCode = failureCode;
        this.sentMessageId = sentMessageId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getConversationId() { return conversationId; }
    @NonNull public String getClientMessageId() { return clientMessageId; }
    @NonNull public String getBody() { return body; }
    @NonNull public Instant getSendAt() { return sendAt; }
    @NonNull public ScheduledMessageStatus getStatus() { return status; }
    @Nullable public String getFailureCode() { return failureCode; }
    @Nullable public String getSentMessageId() { return sentMessageId; }
    @Nullable public Instant getCreatedAt() { return createdAt; }
    @Nullable public Instant getUpdatedAt() { return updatedAt; }

    /** Only a still-PENDING row can be cancelled. */
    public boolean isCancelable() {
        return status == ScheduledMessageStatus.PENDING;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScheduledMessage that)) {
            return false;
        }
        return id.equals(that.id)
                && conversationId.equals(that.conversationId)
                && clientMessageId.equals(that.clientMessageId)
                && body.equals(that.body)
                && sendAt.equals(that.sendAt)
                && status == that.status
                && Objects.equals(failureCode, that.failureCode)
                && Objects.equals(sentMessageId, that.sentMessageId)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, conversationId, clientMessageId, body, sendAt, status,
                failureCode, sentMessageId, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "ScheduledMessage(id=" + id + ", sendAt=" + sendAt
                + ", status=" + status + ", failureCode=" + failureCode + ")";
    }
}
