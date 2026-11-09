package com.nexachat.feature.poll;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Reflective Moshi bean for the versioned poll change feed. */
public final class PollChangesDto {
    @Nullable private final List<ChangeDto> items;
    @Nullable private final Long nextSince;
    @Nullable private final Boolean hasMore;

    public PollChangesDto() {
        this(null, null, null);
    }

    public PollChangesDto(
            @Nullable List<ChangeDto> items,
            @Nullable Long nextSince,
            @Nullable Boolean hasMore) {
        this.items = items;
        this.nextSince = nextSince;
        this.hasMore = hasMore;
    }

    @Nullable public List<ChangeDto> getItems() { return items; }
    @Nullable public Long getNextSince() { return nextSince; }
    public boolean isHasMore() { return Boolean.TRUE.equals(hasMore); }

    public PollChanges toDomain() {
        List<PollChanges.Change> changes = new ArrayList<>();
        if (items != null) {
            for (ChangeDto dto : items) {
                if (dto == null || dto.getPollId() == null) {
                    continue;
                }
                Poll poll = dto.getPoll() == null ? null : dto.getPoll().toDomain();
                if (!dto.isDeleted() && poll == null) {
                    continue;
                }
                changes.add(new PollChanges.Change(
                        dto.getPollId(), dto.getVersion(), dto.isDeleted(), poll));
            }
        }
        return new PollChanges(changes, nextSince == null ? 0L : nextSince, isHasMore());
    }

    /** Reflective Moshi bean for one change item. */
    public static final class ChangeDto {
        @Nullable private final String pollId;
        @Nullable private final Long version;
        @Nullable private final Boolean deleted;
        @Nullable private final PollDto poll;

        public ChangeDto() {
            this(null, null, null, null);
        }

        public ChangeDto(
                @Nullable String pollId,
                @Nullable Long version,
                @Nullable Boolean deleted,
                @Nullable PollDto poll) {
            this.pollId = pollId;
            this.version = version;
            this.deleted = deleted;
            this.poll = poll;
        }

        @Nullable public String getPollId() { return pollId; }
        public long getVersion() { return version == null ? 0L : version; }
        public boolean isDeleted() { return Boolean.TRUE.equals(deleted); }
        @Nullable public PollDto getPoll() { return poll; }
    }
}
