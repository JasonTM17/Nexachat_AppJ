package com.nexachat.call;

import java.util.Objects;

/** Immutable committed call snapshot for optional, non-authoritative status projections. */
public record CallStatusProjectionEvent(CallSession session) {

    public CallStatusProjectionEvent {
        Objects.requireNonNull(session, "session");
    }
}
