package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import java.util.Objects;

/**
 * Robust offline outbox row with atomic lease claim and idempotency support.
 */
@Entity(
        tableName = "offline_outbox",
        indices = {
            @Index(value = {"idempotencyKey"}, unique = true, name = "index_offline_outbox_idempotencyKey")
        })
public final class OfflineOutboxEntity {
    @PrimaryKey @NonNull private final String id;
    @NonNull private final String idempotencyKey;
    @NonNull private final String actionType;
    @NonNull private final String payloadJson;
    @NonNull private final String state;
    private final int retryCount;
    private final long createdAtEpochMs;
    private final long lastAttemptEpochMs;

    public OfflineOutboxEntity(
            @NonNull String id,
            @NonNull String idempotencyKey,
            @NonNull String actionType,
            @NonNull String payloadJson,
            @NonNull String state,
            int retryCount,
            long createdAtEpochMs,
            long lastAttemptEpochMs) {
        this.id = Objects.requireNonNull(id, "id");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
        this.state = Objects.requireNonNull(state, "state");
        this.retryCount = retryCount;
        this.createdAtEpochMs = createdAtEpochMs;
        this.lastAttemptEpochMs = lastAttemptEpochMs;
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getIdempotencyKey() { return idempotencyKey; }
    @NonNull public String getActionType() { return actionType; }
    @NonNull public String getPayloadJson() { return payloadJson; }
    @NonNull public String getState() { return state; }
    public int getRetryCount() { return retryCount; }
    public long getCreatedAtEpochMs() { return createdAtEpochMs; }
    public long getLastAttemptEpochMs() { return lastAttemptEpochMs; }

    @NonNull
    public OfflineOutboxEntity withStateAndAttempt(
            @NonNull String newState, int newRetryCount, long newAttemptEpochMs) {
        return new OfflineOutboxEntity(id, idempotencyKey, actionType, payloadJson,
                newState, newRetryCount, createdAtEpochMs, newAttemptEpochMs);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OfflineOutboxEntity)) return false;
        OfflineOutboxEntity that = (OfflineOutboxEntity) other;
        return retryCount == that.retryCount
                && createdAtEpochMs == that.createdAtEpochMs
                && lastAttemptEpochMs == that.lastAttemptEpochMs
                && Objects.equals(id, that.id)
                && Objects.equals(idempotencyKey, that.idempotencyKey)
                && Objects.equals(actionType, that.actionType)
                && Objects.equals(payloadJson, that.payloadJson)
                && Objects.equals(state, that.state);
    }

    @Override public int hashCode() {
        return Objects.hash(id, idempotencyKey, actionType, payloadJson, state,
                retryCount, createdAtEpochMs, lastAttemptEpochMs);
    }

    @NonNull @Override public String toString() {
        return "OfflineOutboxEntity(id=" + id + ", idempotencyKey=" + idempotencyKey
                + ", actionType=" + actionType + ", payloadJson=" + payloadJson
                + ", state=" + state + ", retryCount=" + retryCount
                + ", createdAtEpochMs=" + createdAtEpochMs
                + ", lastAttemptEpochMs=" + lastAttemptEpochMs + ')';
    }
}
