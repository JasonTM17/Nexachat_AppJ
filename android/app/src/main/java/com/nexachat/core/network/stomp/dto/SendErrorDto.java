package com.nexachat.core.network.stomp.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class SendErrorDto {
    @Nullable private final String code;
    @Nullable private final String clientMessageId;
    @Nullable private final String message;

    public SendErrorDto() {
        this(null, null, null);
    }

    public SendErrorDto(@Nullable String code, @Nullable String clientMessageId, @Nullable String message) {
        this.code = code;
        this.clientMessageId = clientMessageId;
        this.message = message;
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

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SendErrorDto that)) {
            return false;
        }
        return Objects.equals(code, that.code)
                && Objects.equals(clientMessageId, that.clientMessageId)
                && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, clientMessageId, message);
    }
}
