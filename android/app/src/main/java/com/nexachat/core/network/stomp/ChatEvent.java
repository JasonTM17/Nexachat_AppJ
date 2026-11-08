package com.nexachat.core.network.stomp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.ReactionSummaryDto;
import java.util.List;

/** Typed realtime events decoded from the STOMP envelope. */
public sealed interface ChatEvent
        permits ChatEvent.MessageCreated,
                ChatEvent.MessageUpdated,
                ChatEvent.MessageDeleted,
                ChatEvent.Presence,
                ChatEvent.UserTyping,
                ChatEvent.MessageRead,
                ChatEvent.MessageDelivered,
                ChatEvent.ConversationUpdated,
                ChatEvent.ReactionUpdated,
                ChatEvent.PinUpdated,
                ChatEvent.SendRejected,
                ChatEvent.Unknown {

    @Nullable
    String conversationId();

    /** Bean-style accessor for code holding the base event type. */
    @Nullable
    default String getConversationId() {
        return conversationId();
    }

    record MessageCreated(
            @NonNull MessageDto message,
            @Nullable String conversationId,
            @Nullable String serverTs) implements ChatEvent {

        @NonNull
        public MessageDto getMessage() {
            return message;
        }

        @Nullable
        public String getServerTs() {
            return serverTs;
        }
    }

    /** {@code MESSAGE_UPDATED}: the full hydrated message after an edit. */
    record MessageUpdated(
            @NonNull MessageDto message,
            @Nullable String conversationId,
            @Nullable String serverTs) implements ChatEvent {

        @NonNull
        public MessageDto getMessage() {
            return message;
        }

        @Nullable
        public String getServerTs() {
            return serverTs;
        }
    }

    /** {@code MESSAGE_DELETED}: a soft delete. */
    record MessageDeleted(
            @Nullable String conversationId,
            @Nullable String messageId,
            @Nullable String deletedAt) implements ChatEvent {

        @Nullable
        public String getMessageId() {
            return messageId;
        }

        @Nullable
        public String getDeletedAt() {
            return deletedAt;
        }
    }

    /** {@code USER_ONLINE}/{@code USER_OFFLINE} on {@code /user/queue/presence}. */
    record Presence(
            @Nullable String userId,
            boolean online,
            @Nullable String lastSeenAt,
            @Nullable String serverTs) implements ChatEvent {

        public Presence(
                @Nullable String userId,
                boolean online,
                @Nullable String lastSeenAt) {
            this(userId, online, lastSeenAt, null);
        }

        @Nullable
        public String getUserId() {
            return userId;
        }

        public boolean getOnline() {
            return online;
        }

        @Nullable
        public String getLastSeenAt() {
            return lastSeenAt;
        }

        @Nullable
        public String getServerTs() {
            return serverTs;
        }

        @Override
        public String conversationId() {
            return null;
        }
    }

    record UserTyping(
            @Nullable String conversationId,
            @Nullable String userId,
            @Nullable String displayName) implements ChatEvent {

        @Nullable
        public String getUserId() {
            return userId;
        }

        @Nullable
        public String getDisplayName() {
            return displayName;
        }
    }

    record MessageRead(
            @Nullable String conversationId,
            @Nullable String userId,
            @Nullable Long upToSeq) implements ChatEvent {

        @Nullable
        public String getUserId() {
            return userId;
        }

        @Nullable
        public Long getUpToSeq() {
            return upToSeq;
        }
    }

    record MessageDelivered(
            @Nullable String conversationId,
            @Nullable String userId,
            @Nullable Long upToSeq) implements ChatEvent {

        @Nullable
        public String getUserId() {
            return userId;
        }

        @Nullable
        public Long getUpToSeq() {
            return upToSeq;
        }
    }

    record ConversationUpdated(@Nullable String conversationId) implements ChatEvent {
    }

    /** Full reaction summary set; an empty list clears the stored set. */
    record ReactionUpdated(
            @Nullable String conversationId,
            @Nullable String messageId,
            @NonNull List<ReactionSummaryDto> reactions) implements ChatEvent {

        @Nullable
        public String getMessageId() {
            return messageId;
        }

        @NonNull
        public List<ReactionSummaryDto> getReactions() {
            return reactions;
        }
    }

    /** {@code PIN_UPDATED}: add or remove a pin. */
    record PinUpdated(
            @Nullable String conversationId,
            @Nullable String messageId,
            @Nullable Boolean pinned) implements ChatEvent {

        @Nullable
        public String getMessageId() {
            return messageId;
        }

        @Nullable
        public Boolean getPinned() {
            return pinned;
        }
    }

    /** A {@code /user/queue/errors} response to a rejected SEND. */
    record SendRejected(
            @Nullable String code,
            @Nullable String clientMessageId) implements ChatEvent {

        @Override
        public String conversationId() {
            return null;
        }

        /** Java bean accessor retained for the call-signaling client. */
        @Nullable
        public String getCode() {
            return code;
        }

        /** Java bean accessor retained for Java call sites. */
        @Nullable
        public String getClientMessageId() {
            return clientMessageId;
        }
    }

    record Unknown(
            @Nullable String eventType,
            @Nullable String conversationId) implements ChatEvent {

        @Nullable
        public String getEventType() {
            return eventType;
        }
    }
}
