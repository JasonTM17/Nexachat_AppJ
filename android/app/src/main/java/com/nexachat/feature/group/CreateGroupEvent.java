package com.nexachat.feature.group;

import java.util.Objects;

/** One-shot navigation events from create-group. */
public sealed interface CreateGroupEvent permits CreateGroupEvent.Created {

    /** Carries the new conversation id — the nav host routes to {@code chat/{id}}. */
    final class Created implements CreateGroupEvent {
        private final String conversationId;

        public Created(String conversationId) {
            this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        }

        public String getConversationId() {
            return conversationId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Created created
                    && conversationId.equals(created.conversationId);
        }

        @Override
        public int hashCode() {
            return conversationId.hashCode();
        }

        @Override
        public String toString() {
            return "Created(conversationId=" + conversationId + ")";
        }
    }
}
