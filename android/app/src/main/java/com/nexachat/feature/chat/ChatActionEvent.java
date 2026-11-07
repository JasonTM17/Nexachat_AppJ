package com.nexachat.feature.chat;

import java.util.Objects;

/** One-shot chat action results that the UI may surface, such as a forward toast. */
public sealed interface ChatActionEvent
        permits ChatActionEvent.MessageForwarded, ChatActionEvent.MessageEdited {

    final class MessageForwarded implements ChatActionEvent {
        private final String targetConversationId;

        public MessageForwarded(String targetConversationId) {
            this.targetConversationId = Objects.requireNonNull(
                    targetConversationId, "targetConversationId");
        }

        public String getTargetConversationId() {
            return targetConversationId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MessageForwarded that
                    && targetConversationId.equals(that.targetConversationId);
        }

        @Override
        public int hashCode() {
            return targetConversationId.hashCode();
        }

        @Override
        public String toString() {
            return "MessageForwarded(targetConversationId=" + targetConversationId + ")";
        }
    }

    final class MessageEdited implements ChatActionEvent {
        private final String messageId;

        public MessageEdited(String messageId) {
            this.messageId = Objects.requireNonNull(messageId, "messageId");
        }

        public String getMessageId() {
            return messageId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MessageEdited that && messageId.equals(that.messageId);
        }

        @Override
        public int hashCode() {
            return messageId.hashCode();
        }

        @Override
        public String toString() {
            return "MessageEdited(messageId=" + messageId + ")";
        }
    }
}
