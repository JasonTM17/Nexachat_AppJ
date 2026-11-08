package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import java.util.Objects;

/**
 * Persisted call history log row for 1-1 voice and video calls.
 */
@Entity(
        tableName = "call_logs",
        indices = {
            @Index(value = {"startedAt"}, name = "index_call_logs_startedAt")
        })
public final class CallLogEntity {
    @PrimaryKey @NonNull private final String callId;
    @Nullable private final String conversationId;
    @Nullable private final String peerId;
    @Nullable private final String peerDisplayName;
    @Nullable private final String peerAvatarUrl;
    @Nullable private final String direction;
    @Nullable private final String callType;
    private final long startedAt;
    private final long durationSeconds;
    @Nullable private final String endReason;

    public CallLogEntity(
            @NonNull String callId,
            @Nullable String conversationId,
            @Nullable String peerId,
            @Nullable String peerDisplayName,
            @Nullable String peerAvatarUrl,
            @Nullable String direction,
            @Nullable String callType,
            long startedAt,
            long durationSeconds,
            @Nullable String endReason) {
        this.callId = Objects.requireNonNull(callId, "callId");
        this.conversationId = conversationId;
        this.peerId = peerId;
        this.peerDisplayName = peerDisplayName;
        this.peerAvatarUrl = peerAvatarUrl;
        this.direction = direction;
        this.callType = callType;
        this.startedAt = startedAt;
        this.durationSeconds = durationSeconds;
        this.endReason = endReason;
    }

    @NonNull public String getCallId() { return callId; }
    @Nullable public String getConversationId() { return conversationId; }
    @Nullable public String getPeerId() { return peerId; }
    @Nullable public String getPeerDisplayName() { return peerDisplayName; }
    @Nullable public String getPeerAvatarUrl() { return peerAvatarUrl; }
    @Nullable public String getDirection() { return direction; }
    @Nullable public String getCallType() { return callType; }
    public long getStartedAt() { return startedAt; }
    public long getDurationSeconds() { return durationSeconds; }
    @Nullable public String getEndReason() { return endReason; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CallLogEntity)) return false;
        CallLogEntity that = (CallLogEntity) other;
        return startedAt == that.startedAt
                && durationSeconds == that.durationSeconds
                && Objects.equals(callId, that.callId)
                && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(peerId, that.peerId)
                && Objects.equals(peerDisplayName, that.peerDisplayName)
                && Objects.equals(peerAvatarUrl, that.peerAvatarUrl)
                && Objects.equals(direction, that.direction)
                && Objects.equals(callType, that.callType)
                && Objects.equals(endReason, that.endReason);
    }

    @Override public int hashCode() {
        return Objects.hash(callId, conversationId, peerId, peerDisplayName, peerAvatarUrl,
                direction, callType, startedAt, durationSeconds, endReason);
    }

    @NonNull @Override public String toString() {
        return "CallLogEntity(callId=" + callId + ", conversationId=" + conversationId
                + ", peerId=" + peerId + ", peerDisplayName=" + peerDisplayName
                + ", peerAvatarUrl=" + peerAvatarUrl + ", direction=" + direction
                + ", callType=" + callType + ", startedAt=" + startedAt
                + ", durationSeconds=" + durationSeconds + ", endReason=" + endReason + ')';
    }
}
