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
        /** Last reaction event id consumed for this conversation. */
        @Nullable private final Long reactionCursor;
        /** The reaction event log was purged; local reaction state needs a reload. */
        private final boolean reactionsFullRefresh;
        /** Caller's own mute flag; absent payloads leave local state unchanged. */
        @Nullable private final Boolean muted;
        /** Caller's mute expiry as ISO-8601; omitted when forever or unmuted. */
        @Nullable private final String mutedUntil;
        /** Caller's own archive flag; absent payloads leave local state unchanged. */
        @Nullable private final Boolean archived;
        @Nullable private final Boolean pinned;
        @Nullable private final String pinnedAt;

        public ConversationDeltaDto() {
            this(null, List.of(), List.of(), List.of(), List.of(), 0L, false,
                    null, false, null, null, null, null, null);
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
                    reactionUpdates, maxSeq, hasMore, null, false, null, null, null, null, null);
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
            this(conversationId, newMessages, editedMessages, deletedMessageIds,
                    reactionUpdates, maxSeq, hasMore, reactionCursor, reactionsFullRefresh,
                    null, null, null, null, null);
        }

        public ConversationDeltaDto(
                @Nullable String conversationId,
                @Nullable List<MessageDto> newMessages,
                @Nullable List<MessageDto> editedMessages,
                @Nullable List<String> deletedMessageIds,
                @Nullable List<ReactionUpdateDto> reactionUpdates,
                @Nullable Long maxSeq,
                boolean hasMore,
                @Nullable Boolean muted,
                @Nullable String mutedUntil,
                @Nullable Boolean archived) {
            this(conversationId, newMessages, editedMessages, deletedMessageIds,
                    reactionUpdates, maxSeq, hasMore, null, false, muted, mutedUntil, archived, null, null);
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
                boolean reactionsFullRefresh,
                @Nullable Boolean muted,
                @Nullable String mutedUntil,
                @Nullable Boolean archived) {
            this(conversationId, newMessages, editedMessages, deletedMessageIds,
                    reactionUpdates, maxSeq, hasMore, reactionCursor, reactionsFullRefresh,
                    muted, mutedUntil, archived, null, null);
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
                boolean reactionsFullRefresh,
                @Nullable Boolean muted,
                @Nullable String mutedUntil,
                @Nullable Boolean archived,
                @Nullable Boolean pinned,
                @Nullable String pinnedAt) {
            this.conversationId = conversationId;
            this.newMessages = newMessages == null ? List.of() : newMessages;
            this.editedMessages = editedMessages == null ? List.of() : editedMessages;
            this.deletedMessageIds = deletedMessageIds == null ? List.of() : deletedMessageIds;
            this.reactionUpdates = reactionUpdates == null ? List.of() : reactionUpdates;
            this.maxSeq = maxSeq == null ? 0L : maxSeq;
            this.hasMore = hasMore;
            this.reactionCursor = reactionCursor;
            this.reactionsFullRefresh = reactionsFullRefresh;
            this.muted = muted;
            this.mutedUntil = mutedUntil;
            this.archived = archived;
            this.pinned = pinned;
            this.pinnedAt = pinnedAt;
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
                boolean reactionsFullRefresh,
                @Nullable Boolean pinned,
                @Nullable String pinnedAt) {
            this(conversationId, newMessages, editedMessages, deletedMessageIds,
                    reactionUpdates, maxSeq, hasMore, reactionCursor, reactionsFullRefresh,
                    null, null, null, pinned, pinnedAt);
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
        @Nullable public Boolean getMuted() { return muted; }
        @Nullable public String getMutedUntil() { return mutedUntil; }
        @Nullable public Boolean getArchived() { return archived; }
        @Nullable public Boolean getPinned() { return pinned; }
        public boolean isPinned() { return Boolean.TRUE.equals(pinned); }
        @Nullable public String getPinnedAt() { return pinnedAt; }
    }
}
