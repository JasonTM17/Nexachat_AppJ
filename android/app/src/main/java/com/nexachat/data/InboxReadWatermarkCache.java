package com.nexachat.data;

import androidx.annotation.NonNull;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Monotonic caller read watermarks learned from inbox REST payloads. */
@Singleton
public final class InboxReadWatermarkCache {

    private final ConcurrentHashMap<String, Long> byConversation = new ConcurrentHashMap<>();

    @Inject
    public InboxReadWatermarkCache() {
    }

    public void adopt(@NonNull String conversationId, long lastReadSeq) {
        if (lastReadSeq <= 0L) {
            return;
        }
        byConversation.merge(conversationId, lastReadSeq, Math::max);
    }

    public long peek(@NonNull String conversationId) {
        Long seq = byConversation.get(conversationId);
        return seq == null ? 0L : seq;
    }
}
