package com.nexachat.core.network.stomp.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class SendErrorDto {
    @Nullable private final String code;
    @Nullable private final String clientMessageId;
    @Nullable private final String message;
    @Nullable private final String callId;
    @Nullable private final String conversationId;

    public SendErrorDto() {
        this(null, null, null, null, null);
    }

    public SendErrorDto(@Nullable String code, @Nullable String clientMessageId, @Nullable String message) {
        this(code, clientMessageId, message, null, null);
    }

    public SendErrorDto(@Nullable String code, @Nullable String clientMessageId, @Nullable String message,
                        @Nullable String callId, @Nullable String conversationId) {
        this.code = code;
        this.clientMessageId = clientMessageId;
        this.message = message;
        this.callId = callId;
        this.conversationId = conversationId;
    }

    @Nullable public String getCode() {
        return code;
    }

    @Nullable public String getClientMessageId() {
        return clientMessageId;
    }

    @Nullable public String getMessage() {
        return message;
    }

    @Nullable public String getCallId() {
        return callId;
    }

    @Nullable public String getConversationId() {
        return conversationId;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SendErrorDto that)) {
            return false;
        }
        return Objects.equals(code, that.code)
                && Objects.equals(clientMessageId, that.clientMessageId)
                && Objects.equals(message, that.message)
                && Objects.equals(callId, that.callId)
                && Objects.equals(conversationId, that.conversationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, clientMessageId, message, callId, conversationId);
    }
}
