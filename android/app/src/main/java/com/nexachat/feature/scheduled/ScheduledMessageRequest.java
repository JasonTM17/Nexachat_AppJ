package com.nexachat.feature.scheduled;

import androidx.annotation.Nullable;

/**
 * Reflective Moshi bean for the create scheduled message body. {@code clientMessageId}
 * doubles as the idempotency handle: the server replays a still-PENDING row for the
 * same value instead of writing a second one.
 */
public final class ScheduledMessageRequest {
    @Nullable private final String clientMessageId;
    @Nullable private final String body;
    @Nullable private final String sendAt;

    public ScheduledMessageRequest() {
        this(null, null, null);
    }

    public ScheduledMessageRequest(
            @Nullable String clientMessageId,
            @Nullable String body,
            @Nullable String sendAt) {
        this.clientMessageId = clientMessageId;
        this.body = body;
        this.sendAt = sendAt;
    }

    @Nullable public String getClientMessageId() { return clientMessageId; }
    @Nullable public String getBody() { return body; }
    @Nullable public String getSendAt() { return sendAt; }
}
