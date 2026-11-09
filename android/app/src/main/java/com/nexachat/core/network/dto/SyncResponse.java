package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

/**
 * Payload returned by POST /api/v1/sync containing delta bundles per conversation.
 */
public final class SyncResponse {
    @Nullable private final List<ConversationDeltaDto> deltas;
    @Nullable private final String serverTime;

    public SyncResponse() {
        this(List.of(), null);
    }

    public SyncResponse(@Nullable List<ConversationDeltaDto> deltas, @Nullable String serverTime) {
        this.deltas = deltas == null ? List.of() : deltas;
        this.serverTime = serverTime;
    }

    @NonNull public List<ConversationDeltaDto> getDeltas() {
        return deltas == null ? List.of() : deltas;
    }

    @Nullable public String getServerTime() {
        return serverTime;
    }

    public static final class ConversationDeltaDto {
        @Nullable private final String conversationId;
        @Nullable private final List<MessageDto> newMessages;
        @Nullable private final List<MessageDto> editedMessages;
        @Nullable private final List<String> deletedMessageIds;
        @Nullable private final List<ReactionUpdateDto> reactionUpdates;
        @Nullable private final Long maxSeq;
        private final boolean hasMore;
        @Nullable private final Long reactionCursor;
        private final boolean reactionsFullRefresh;

        public ConversationDeltaDto() {
            this(null, List.of(), List.of(), List.of(), List.of(), 0L, false, null, false);
        }

        public ConversationDeltaDto(
                @Nullable String conversationId,
                @Nullable List<MessageDto> newMessages,
                @Nullable List<MessageDto> editedMessages,
                @Nullable List<String> deletedMessageIds,
                @Nullable List<ReactionUpdateDto> reactionUpdates,
                @Nullable Long maxSeq,
                boolean hasMore) {
            this(conversationId, newMessages, editedMessages, deletedMessageIds,
                    reactionUpdates, maxSeq, hasMore, null, false);
        }

        public ConversationDeltaDto(
                @Nullable String conversationId,
                @Nullable List<MessageDto> newMessages,
                @Nullable List<MessageDto> editedMessages,
                @Nullable List<String> deletedMessageIds,
                @Nullable List<ReactionUpdateDto> reactionUpdates,
                @Nullable Long maxSeq,
                boolean hasMore,
                @Nullable Long reactionCursor,
                boolean reactionsFullRefresh) {
            this.conversationId = conversationId;
            this.newMessages = newMessages == null ? List.of() : newMessages;
            this.editedMessages = editedMessages == null ? List.of() : editedMessages;
            this.deletedMessageIds = deletedMessageIds == null ? List.of() : deletedMessageIds;
            this.reactionUpdates = reactionUpdates == null ? List.of() : reactionUpdates;
            this.maxSeq = maxSeq == null ? 0L : maxSeq;
            this.hasMore = hasMore;
            this.reactionCursor = reactionCursor;
            this.reactionsFullRefresh = reactionsFullRefresh;
        }

        @Nullable public String getConversationId() { return conversationId; }
        @NonNull public List<MessageDto> getNewMessages() { return newMessages == null ? List.of() : newMessages; }
        @NonNull public List<MessageDto> getEditedMessages() { return editedMessages == null ? List.of() : editedMessages; }
        @NonNull public List<String> getDeletedMessageIds() { return deletedMessageIds == null ? List.of() : deletedMessageIds; }
        @NonNull public List<ReactionUpdateDto> getReactionUpdates() { return reactionUpdates == null ? List.of() : reactionUpdates; }
        public long getMaxSeq() { return maxSeq == null ? 0L : maxSeq; }
        public boolean isHasMore() { return hasMore; }
        @Nullable public Long getReactionCursor() { return reactionCursor; }
        public boolean isReactionsFullRefresh() { return reactionsFullRefresh; }
    }
}
