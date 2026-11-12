package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Payload sent to POST /api/v1/sync to request message deltas across conversations.
 */
public final class SyncRequest {
    @NonNull private final List<Watermark> watermarks;
    @Nullable private final Integer limitPerConv;
    @Nullable private final String since;
    @Nullable private final Integer syncVersion;

    public SyncRequest(@NonNull List<Watermark> watermarks, @Nullable Integer limitPerConv) {
        this(watermarks, limitPerConv, null);
    }

    public SyncRequest(
            @NonNull List<Watermark> watermarks,
            @Nullable Integer limitPerConv,
            @Nullable String since) {
        this(watermarks, limitPerConv, since, null);
    }

    public SyncRequest(@NonNull List<Watermark> watermarks, @Nullable Integer limitPerConv,
            @Nullable String since, @Nullable Integer syncVersion) {
        this.syncVersion = syncVersion;
        this.watermarks = Objects.requireNonNull(watermarks, "watermarks");
        this.limitPerConv = limitPerConv;
        this.since = since;
    }

    @Nullable public Integer getSyncVersion() { return syncVersion; }
    @NonNull public List<Watermark> getWatermarks() { return watermarks; }
    @Nullable public Integer getLimitPerConv() { return limitPerConv; }
    @Nullable public String getSince() { return since; }

    public static final class Watermark {
        @NonNull private final String conversationId;
        private final long lastSeq;
        /**
         * Last reaction event id consumed for this conversation; echoed only
         * while the server reports a truncated reaction page.
         */
        @Nullable private final Long reactionCursor;

        @Nullable private final Long changeCursor;
        @Nullable private final Long generation;

        public Watermark(@NonNull String conversationId, long lastSeq) {
            this(conversationId, lastSeq, null);
        }

        public Watermark(
                @NonNull String conversationId, long lastSeq, @Nullable Long reactionCursor) {
            this(conversationId, lastSeq, reactionCursor, null, null);
        }

        public Watermark(@NonNull String conversationId, long lastSeq, @Nullable Long reactionCursor,
                @Nullable Long changeCursor, @Nullable Long generation) {
            this.changeCursor = changeCursor;
            this.generation = generation;
            this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
            this.lastSeq = lastSeq;
            this.reactionCursor = reactionCursor;
        }

        @Nullable public Long getChangeCursor() { return changeCursor; }
        @Nullable public Long getGeneration() { return generation; }
        @NonNull public String getConversationId() { return conversationId; }
        public long getLastSeq() { return lastSeq; }
        @Nullable public Long getReactionCursor() { return reactionCursor; }
    }
}
