package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One option and its current result. Voter ids are always empty for anonymous polls. */
public final class PollOption {

    private final String id;
    private final int position;
    private final String label;
    private final int voteCount;
    private final List<String> voterIds;

    public PollOption(
            @NonNull String id,
            int position,
            @NonNull String label,
            int voteCount,
            @NonNull List<String> voterIds) {
        this.id = Objects.requireNonNull(id, "id");
        this.position = position;
        this.label = Objects.requireNonNull(label, "label");
        this.voteCount = Math.max(0, voteCount);
        this.voterIds = Collections.unmodifiableList(new ArrayList<>(voterIds));
    }

    @NonNull public String getId() { return id; }
    public int getPosition() { return position; }
    @NonNull public String getLabel() { return label; }
    public int getVoteCount() { return voteCount; }
    @NonNull public List<String> getVoterIds() { return voterIds; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PollOption that)) {
            return false;
        }
        return position == that.position
                && voteCount == that.voteCount
                && id.equals(that.id)
                && label.equals(that.label)
                && voterIds.equals(that.voterIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, position, label, voteCount, voterIds);
    }
}
