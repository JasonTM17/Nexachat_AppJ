package com.nexachat.data;

import androidx.annotation.Nullable;

/** Peer-is-typing signal for the open chat. */
public record TypingEvent(
        String conversationId, @Nullable String userId, @Nullable String displayName) {}
