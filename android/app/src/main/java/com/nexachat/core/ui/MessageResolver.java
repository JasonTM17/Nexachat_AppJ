package com.nexachat.core.ui;

import com.nexachat.data.ChatMessage;
import java.util.List;

/**
 * Pure Java helper for resolving messages from id across configuration changes (D1, D5).
 * Enforces graceful degradation when a message is missing or deleted.
 */
public final class MessageResolver {

    private MessageResolver() {}

    /**
     * Resolves a message from a list by its unique ID.
     * Returns null if the list is null, ID is null/empty, or message is not found.
     */
    public static ChatMessage findById(List<ChatMessage> messages, String messageId) {
        if (messages == null || messageId == null || messageId.trim().isEmpty()) {
            return null;
        }
        for (ChatMessage msg : messages) {
            if (msg != null && messageId.equals(msg.getId())) {
                return msg;
            }
        }
        return null;
    }

    /**
     * Resolves a message for editing.
     * Returns null if the message is missing, or if it is a tombstone (deleted).
     */
    public static ChatMessage resolveForEdit(List<ChatMessage> messages, String messageId) {
        ChatMessage msg = findById(messages, messageId);
        if (msg == null) {
            return null;
        }
        if (msg.isDeleted() || msg.getDeletedAtEpochMs() != null) {
            return null;
        }
        return msg;
    }

    /**
     * Resolves a message for displaying an action bottom sheet.
     * Returns null if the message is missing or deleted.
     */
    public static ChatMessage resolveForActionSheet(List<ChatMessage> messages, String messageId) {
        ChatMessage msg = findById(messages, messageId);
        if (msg == null) {
            return null;
        }
        if (msg.isDeleted() || msg.getDeletedAtEpochMs() != null) {
            return null;
        }
        return msg;
    }
}
