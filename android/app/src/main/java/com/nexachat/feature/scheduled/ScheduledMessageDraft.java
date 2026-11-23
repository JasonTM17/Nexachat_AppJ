package com.nexachat.feature.scheduled;

import androidx.annotation.NonNull;
import java.time.Instant;
import java.util.Objects;

/** One create-scheduled payload; equality drives clientMessageId reuse on retry. */
public final class ScheduledMessageDraft {

    private final String body;
    private final Instant sendAt;

    public ScheduledMessageDraft(@NonNull String body, @NonNull Instant sendAt) {
        this.body = Objects.requireNonNull(body, "body");
        this.sendAt = Objects.requireNonNull(sendAt, "sendAt");
    }

    @NonNull public String getBody() { return body; }
    @NonNull public Instant getSendAt() { return sendAt; }

    /** Trims the body the same way the composer send path does. */
    @NonNull
    public ScheduledMessageDraft normalized() {
        return new ScheduledMessageDraft(body.trim(), sendAt);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScheduledMessageDraft that)) {
            return false;
        }
        return body.equals(that.body) && sendAt.equals(that.sendAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(body, sendAt);
    }
}
