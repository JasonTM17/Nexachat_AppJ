package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Unsent composer text kept per conversation, like WhatsApp/iMessage drafts.
 * Local-only: leaving a chat must not lose the box, and inbox rows show a
 * {@code Draft:} preview until the send succeeds.
 */
@Entity(tableName = "composer_drafts")
public final class ComposerDraftEntity {
    @PrimaryKey @NonNull private final String conversationId;
    @NonNull private final String content;
    @NonNull private final String mentionsJson;
    private final int mentionAllOffset;
    private final int mentionAllLength;
    private final long updatedAtEpochMs;

    public ComposerDraftEntity(
            @NonNull String conversationId,
            @NonNull String content,
            @NonNull String mentionsJson,
            int mentionAllOffset,
            int mentionAllLength,
            long updatedAtEpochMs) {
        this.conversationId = conversationId;
        this.content = content;
        this.mentionsJson = mentionsJson;
        this.mentionAllOffset = mentionAllOffset;
        this.mentionAllLength = mentionAllLength;
        this.updatedAtEpochMs = updatedAtEpochMs;
    }

    @NonNull public String getConversationId() {
        return conversationId;
    }

    @NonNull public String getContent() {
        return content;
    }

    @NonNull public String getMentionsJson() {
        return mentionsJson;
    }

    public int getMentionAllOffset() {
        return mentionAllOffset;
    }

    public int getMentionAllLength() {
        return mentionAllLength;
    }

    public long getUpdatedAtEpochMs() {
        return updatedAtEpochMs;
    }
}
