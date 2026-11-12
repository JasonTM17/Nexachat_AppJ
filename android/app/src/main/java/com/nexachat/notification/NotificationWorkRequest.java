package com.nexachat.notification;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Data;
import androidx.work.WorkerParameters;
import java.time.Instant;

/** Immutable, validated input for one account-bound notification occurrence. */
public final class NotificationWorkRequest {
    public static final String TYPE_MESSAGE = "MESSAGE";
    public static final String TYPE_REMINDER = "REMINDER";

    private static final String KEY_TYPE = "type";
    private static final String KEY_RECIPIENT = "recipientId";
    private static final String KEY_EPOCH = "sessionEpoch";
    private static final String KEY_CONVERSATION = "conversationId";
    private static final String KEY_MESSAGE = "messageId";
    private static final String KEY_SEQUENCE = "seq";
    private static final String KEY_REMINDER = "reminderId";
    private static final String KEY_REMIND_AT = "remindAt";

    private final String type;
    private final String recipientId;
    private final long sessionEpoch;
    private final String conversationId;
    @Nullable private final String messageId;
    private final long sequence;
    @Nullable private final String reminderId;
    @Nullable private final String remindAt;

    private NotificationWorkRequest(
            String type,
            String recipientId,
            long sessionEpoch,
            String conversationId,
            @Nullable String messageId,
            long sequence,
            @Nullable String reminderId,
            @Nullable String remindAt) {
        this.type = type;
        this.recipientId = recipientId;
        this.sessionEpoch = sessionEpoch;
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.sequence = sequence;
        this.reminderId = reminderId;
        this.remindAt = remindAt;
    }

    @NonNull
    public static NotificationWorkRequest message(
            @NonNull String recipientId,
            long sessionEpoch,
            @NonNull String conversationId,
            @NonNull String messageId,
            long sequence) {
        return new NotificationWorkRequest(TYPE_MESSAGE, recipientId, sessionEpoch,
                conversationId, messageId, sequence, null, null);
    }

    @NonNull
    public static NotificationWorkRequest reminder(
            @NonNull String recipientId,
            long sessionEpoch,
            @NonNull String conversationId,
            @NonNull String reminderId,
            @NonNull String remindAt) {
        return new NotificationWorkRequest(TYPE_REMINDER, recipientId, sessionEpoch,
                conversationId, null, 0L, reminderId, remindAt);
    }

    @Nullable
    public static NotificationWorkRequest fromData(@Nullable Data data) {
        if (data == null) return null;
        NotificationWorkRequest request = new NotificationWorkRequest(
                data.getString(KEY_TYPE),
                data.getString(KEY_RECIPIENT),
                data.getLong(KEY_EPOCH, -1L),
                data.getString(KEY_CONVERSATION),
                data.getString(KEY_MESSAGE),
                data.getLong(KEY_SEQUENCE, 0L),
                data.getString(KEY_REMINDER),
                data.getString(KEY_REMIND_AT));
        return request.isValid() ? request : null;
    }

    @Nullable
    public static NotificationWorkRequest fromIntent(@Nullable Intent intent) {
        if (intent == null) return null;
        Bundle extras = intent.getExtras();
        if (extras == null) return null;
        NotificationWorkRequest request = new NotificationWorkRequest(
                extras.getString(KEY_TYPE),
                extras.getString(KEY_RECIPIENT),
                extras.getLong(KEY_EPOCH, -1L),
                extras.getString(KEY_CONVERSATION),
                extras.getString(KEY_MESSAGE),
                extras.getLong(KEY_SEQUENCE, 0L),
                extras.getString(KEY_REMINDER),
                extras.getString(KEY_REMIND_AT));
        return request.isValid() ? request : null;
    }

    public boolean isValid() {
        if (!hasText(recipientId) || sessionEpoch <= 0L || !hasText(conversationId)) {
            return false;
        }
        if (TYPE_MESSAGE.equals(type)) {
            return hasText(messageId) && sequence > 0L;
        }
        if (TYPE_REMINDER.equals(type)) {
            if (!hasText(reminderId) || !hasText(remindAt)) return false;
            try {
                Instant.parse(remindAt);
                return true;
            } catch (RuntimeException malformed) {
                return false;
            }
        }
        return false;
    }

    @NonNull
    public Data toData() {
        Data.Builder builder = new Data.Builder()
                .putString(KEY_TYPE, type)
                .putString(KEY_RECIPIENT, recipientId)
                .putLong(KEY_EPOCH, sessionEpoch)
                .putString(KEY_CONVERSATION, conversationId);
        if (messageId != null) {
            builder.putString(KEY_MESSAGE, messageId).putLong(KEY_SEQUENCE, sequence);
        }
        if (reminderId != null) {
            builder.putString(KEY_REMINDER, reminderId).putString(KEY_REMIND_AT, remindAt);
        }
        return builder.build();
    }

    public void putInIntent(@NonNull Intent intent) {
        intent.putExtra(KEY_TYPE, type)
                .putExtra(KEY_RECIPIENT, recipientId)
                .putExtra(KEY_EPOCH, sessionEpoch)
                .putExtra(KEY_CONVERSATION, conversationId);
        if (messageId != null) {
            intent.putExtra(KEY_MESSAGE, messageId).putExtra(KEY_SEQUENCE, sequence);
        }
        if (reminderId != null) {
            intent.putExtra(KEY_REMINDER, reminderId).putExtra(KEY_REMIND_AT, remindAt);
        }
    }

    @NonNull
    public String uniqueWorkName() {
        return TYPE_MESSAGE.equals(type)
                ? "msg:" + recipientId + ":" + sessionEpoch + ":" + messageId
                : "rem:" + recipientId + ":" + sessionEpoch + ":" + reminderId + ":" + remindAt;
    }

    @NonNull
    public String occurrenceKey() {
        return uniqueWorkName();
    }

    @NonNull public String getType() { return type; }
    @NonNull public String getRecipientId() { return recipientId; }
    public long getSessionEpoch() { return sessionEpoch; }
    @NonNull public String getConversationId() { return conversationId; }
    @Nullable public String getMessageId() { return messageId; }
    public long getSequence() { return sequence; }
    @Nullable public String getReminderId() { return reminderId; }
    @Nullable public String getRemindAt() { return remindAt; }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
