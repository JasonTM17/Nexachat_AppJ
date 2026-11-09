package com.nexachat.feature.poll;

import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Reflective Moshi bean for one poll option. {@code voterIds} is omitted on anonymous polls. */
public final class PollOptionDto {
    @Nullable private final String id;
    @Nullable private final Integer position;
    @Nullable private final String label;
    @Nullable private final Integer voteCount;
    @Nullable private final List<String> voterIds;

    public PollOptionDto() {
        this(null, null, null, null, null);
    }

    public PollOptionDto(
            @Nullable String id,
            @Nullable Integer position,
            @Nullable String label,
            @Nullable Integer voteCount,
            @Nullable List<String> voterIds) {
        this.id = id;
        this.position = position;
        this.label = label;
        this.voteCount = voteCount;
        this.voterIds = voterIds;
    }

    @Nullable public String getId() { return id; }
    @Nullable public Integer getPosition() { return position; }
    @Nullable public String getLabel() { return label; }
    @Nullable public Integer getVoteCount() { return voteCount; }
    @Nullable public List<String> getVoterIds() { return voterIds; }

    @Nullable
    public PollOption toDomain(boolean anonymous) {
        if (isBlank(id) || isBlank(label)) {
            return null;
        }
        List<String> voters = new ArrayList<>();
        if (!anonymous && voterIds != null) {
            for (String voterId : voterIds) {
                if (!isBlank(voterId)) {
                    voters.add(voterId);
                }
            }
        }
        return new PollOption(
                id,
                position == null ? 0 : position,
                label,
                voteCount == null ? voters.size() : voteCount,
                voters);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
