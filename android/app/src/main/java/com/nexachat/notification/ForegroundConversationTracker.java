package com.nexachat.notification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Tracks the visible chat so its own push cannot interrupt the active conversation. */
@Singleton
public final class ForegroundConversationTracker {
    private final AtomicReference<String> currentConversation = new AtomicReference<>();

    @Inject
    public ForegroundConversationTracker() { }

    public void onConversationResumed(@NonNull String conversationId) {
        if (!conversationId.isBlank()) currentConversation.set(conversationId);
    }

    public void onConversationPaused(@NonNull String conversationId) {
        currentConversation.compareAndSet(conversationId, null);
    }

    public boolean isForegroundConversation(@Nullable String conversationId) {
        return conversationId != null && conversationId.equals(currentConversation.get());
    }
}
