package com.nexachat.feature.scheduled;

import androidx.annotation.Nullable;

/**
 * Reflective Moshi bean for the edit scheduled message body. Both fields are
 * optional server-side, but the editor always sends the normalized pair so the
 * row cannot be half-updated by a stale client.
 */
public final class ScheduledMessageUpdateRequest {
    @Nullable private final String body;
    @Nullable private final String sendAt;

    public ScheduledMessageUpdateRequest() {
        this(null, null);
    }

    public ScheduledMessageUpdateRequest(@Nullable String body, @Nullable String sendAt) {
        this.body = body;
        this.sendAt = sendAt;
    }

    @Nullable public String getBody() { return body; }
    @Nullable public String getSendAt() { return sendAt; }
}
