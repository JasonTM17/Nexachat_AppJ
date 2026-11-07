package com.nexachat.data;

import androidx.annotation.Nullable;

/** Read watermark for the open conversation. */
public record ReadEvent(
        String conversationId, @Nullable String userId, @Nullable Long upToSeq) {}
