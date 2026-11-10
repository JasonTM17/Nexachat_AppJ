package com.nexachat.feature.chat.adapter;

import androidx.annotation.Nullable;
import com.nexachat.data.ChatMessage;

/** Meta row visibility for system and ordinary chat rows. */
public final class MessageBubbleMetaRules {

    public enum MetaRowPolicy {
        HIDDEN,
        TIMESTAMP_WITHOUT_RECEIPTS,
        FULL
    }

    private MessageBubbleMetaRules() {
    }

    public static MetaRowPolicy policyFor(@Nullable ChatMessage message) {
        if (message == null) {
            return MetaRowPolicy.FULL;
        }
        if (MessageViewTypeMapper.isSystemMessage(message)) {
            return MetaRowPolicy.TIMESTAMP_WITHOUT_RECEIPTS;
        }
        return MetaRowPolicy.FULL;
    }
}
