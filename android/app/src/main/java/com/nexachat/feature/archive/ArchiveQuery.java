package com.nexachat.feature.archive;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Immutable input to one archive page request. */
public final class ArchiveQuery {

    public static final int PAGE_SIZE = 30;

    private final String conversationId;
    private final ArchiveType type;
    @Nullable private final String senderId;
    @Nullable private final String from;
    @Nullable private final String to;
    private final int limit;
    @Nullable private final String cursor;

    public ArchiveQuery(
            String conversationId,
            ArchiveType type,
            @Nullable String senderId,
            @Nullable String from,
            @Nullable String to,
            int limit,
            @Nullable String cursor) {
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.type = Objects.requireNonNull(type, "type");
        this.senderId = senderId;
        this.from = from;
        this.to = to;
        this.limit = limit;
        this.cursor = cursor;
    }

    public String getConversationId() { return conversationId; }
    public ArchiveType getType() { return type; }
    @Nullable public String getSenderId() { return senderId; }
    @Nullable public String getFrom() { return from; }
    @Nullable public String getTo() { return to; }
    public int getLimit() { return limit; }
    @Nullable public String getCursor() { return cursor; }
}
