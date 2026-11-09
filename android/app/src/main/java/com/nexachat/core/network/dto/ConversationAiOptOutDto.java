package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;

/** Caller-scoped AI opt-out state returned by {@code /conversations/{id}/ai-opt-out}. */
public final class ConversationAiOptOutDto {
    @Nullable private final String conversationId;
    @Nullable private final Boolean aiOptOut;

    public ConversationAiOptOutDto() {
        this(null, null);
    }

    public ConversationAiOptOutDto(@Nullable String conversationId, @Nullable Boolean aiOptOut) {
        this.conversationId = conversationId;
        this.aiOptOut = aiOptOut;
    }

    @Nullable public String getConversationId() {
        return conversationId;
    }

    /** The caller's own state; another member's choice is never present. */
    @Nullable public Boolean getAiOptOut() {
        return aiOptOut;
    }
}
