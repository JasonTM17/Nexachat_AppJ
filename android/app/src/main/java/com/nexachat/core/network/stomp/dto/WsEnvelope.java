package com.nexachat.core.network.stomp.dto;

import androidx.annotation.Nullable;
import java.util.Map;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class WsEnvelope {
    @Nullable private final String eventType;
    @Nullable private final String conversationId;
    @Nullable private final String serverTs;
    @Nullable private final Map<String, Object> payload;

    public WsEnvelope() {
        this(null, null, null, null);
    }

    public WsEnvelope(@Nullable String eventType, @Nullable String conversationId, @Nullable String serverTs, @Nullable Map<String, Object> payload) {
        this.eventType = eventType;
        this.conversationId = conversationId;
        this.serverTs = serverTs;
        this.payload = payload;
    }

    @Nullable public String getEventType() {
        return eventType;
    }

    @Nullable public String getConversationId() {
        return conversationId;
    }

    @Nullable public String getServerTs() {
        return serverTs;
    }

    @Nullable public Map<String, Object> getPayload() {
        return payload;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof WsEnvelope that)) {
            return false;
        }
        return Objects.equals(eventType, that.eventType)
                && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(serverTs, that.serverTs)
                && Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventType, conversationId, serverTs, payload);
    }
}
