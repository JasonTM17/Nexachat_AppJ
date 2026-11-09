package com.nexachat.feature.archive;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Typed navigation values for opening the archive and returning to a message. */
public final class ArchiveNavigationArgs {

    private final String conversationId;
    @Nullable private final String messageId;

    private ArchiveNavigationArgs(String conversationId, @Nullable String messageId) {
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.messageId = messageId;
    }

    public static ArchiveNavigationArgs forConversation(String conversationId) {
        return new ArchiveNavigationArgs(conversationId, null);
    }

    public static ArchiveNavigationArgs forOriginalMessage(
            String conversationId,
            String messageId) {
        return new ArchiveNavigationArgs(
                conversationId, Objects.requireNonNull(messageId, "messageId"));
    }

    public String getConversationId() { return conversationId; }
    @Nullable public String getMessageId() { return messageId; }
    public boolean hasMessageTarget() { return messageId != null && !messageId.isBlank(); }
}
