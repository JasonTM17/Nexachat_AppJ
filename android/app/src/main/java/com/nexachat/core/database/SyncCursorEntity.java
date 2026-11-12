package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;

/** One active generation per account/conversation; checkpoints commit with the applied page. */
@Entity(tableName = "sync_cursors",
        primaryKeys = {"ownerUserId", "conversationId", "generation"},
        indices = {@Index(value = {"ownerUserId", "conversationId"}, unique = true)})
public final class SyncCursorEntity {
    @NonNull public final String ownerUserId;
    @NonNull public final String conversationId;
    public final long generation;
    public final long changeCursor;
    @Nullable public final String reactionSince;
    @Nullable public final Long reactionCursor;
    @Nullable public final String reactionBaseline;
    public final boolean rebuildingReactions;

    public SyncCursorEntity(@NonNull String ownerUserId, @NonNull String conversationId,
            long generation, long changeCursor, @Nullable String reactionSince,
            @Nullable Long reactionCursor, @Nullable String reactionBaseline,
            boolean rebuildingReactions) {
        this.ownerUserId = ownerUserId;
        this.conversationId = conversationId;
        this.generation = generation;
        this.changeCursor = changeCursor;
        this.reactionSince = reactionSince;
        this.reactionCursor = reactionCursor;
        this.reactionBaseline = reactionBaseline;
        this.rebuildingReactions = rebuildingReactions;
    }
}
