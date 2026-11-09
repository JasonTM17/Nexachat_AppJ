package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

/** Compacted per-conversation change feed page. */
public final class PollChanges {

    private final List<Change> items;
    private final long nextSince;
    private final boolean hasMore;

    public PollChanges(@NonNull List<Change> items, long nextSince, boolean hasMore) {
        this.items = List.copyOf(items);
        this.nextSince = nextSince;
        this.hasMore = hasMore;
    }

    @NonNull public List<Change> getItems() { return items; }
    public long getNextSince() { return nextSince; }
    public boolean hasMore() { return hasMore; }

    /** One revision: a full poll current state or a delete tombstone. */
    public static final class Change {
        private final String pollId;
        private final long version;
        private final boolean deleted;
        @Nullable private final Poll poll;

        public Change(
                @NonNull String pollId,
                long version,
                boolean deleted,
                @Nullable Poll poll) {
            this.pollId = pollId;
            this.version = version;
            this.deleted = deleted;
            this.poll = poll;
        }

        @NonNull public String getPollId() { return pollId; }
        public long getVersion() { return version; }
        public boolean isDeleted() { return deleted; }
        @Nullable public Poll getPoll() { return poll; }
    }
}
