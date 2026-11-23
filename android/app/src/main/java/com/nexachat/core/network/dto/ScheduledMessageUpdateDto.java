package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Reflective Moshi bean for the {@code SCHEDULED_MESSAGE_UPDATED} inbox payload.
 * Absent JSON keeps the no-arg defaults.
 */
public final class ScheduledMessageUpdateDto {
    @Nullable private final String scheduledId;
    @Nullable private final String clientMessageId;
    @Nullable private final String status;
    @Nullable private final String failureCode;
    @Nullable private final String sendAt;
    @Nullable private final String sentMessageId;

    public ScheduledMessageUpdateDto() {
        this(null, null, null, null, null, null);
    }

    public ScheduledMessageUpdateDto(
            @Nullable String scheduledId,
            @Nullable String clientMessageId,
            @Nullable String status,
            @Nullable String failureCode,
            @Nullable String sendAt,
            @Nullable String sentMessageId) {
        this.scheduledId = scheduledId;
        this.clientMessageId = clientMessageId;
        this.status = status;
        this.failureCode = failureCode;
        this.sendAt = sendAt;
        this.sentMessageId = sentMessageId;
    }

    @Nullable public String getScheduledId() { return scheduledId; }
    @Nullable public String getClientMessageId() { return clientMessageId; }
    @Nullable public String getStatus() { return status; }
    @Nullable public String getFailureCode() { return failureCode; }
    @Nullable public String getSendAt() { return sendAt; }
    @Nullable public String getSentMessageId() { return sentMessageId; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ScheduledMessageUpdateDto that)) {
            return false;
        }
        return Objects.equals(scheduledId, that.scheduledId)
                && Objects.equals(clientMessageId, that.clientMessageId)
                && Objects.equals(status, that.status)
                && Objects.equals(failureCode, that.failureCode)
                && Objects.equals(sendAt, that.sendAt)
                && Objects.equals(sentMessageId, that.sentMessageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheduledId, clientMessageId, status, failureCode, sendAt, sentMessageId);
    }
}
