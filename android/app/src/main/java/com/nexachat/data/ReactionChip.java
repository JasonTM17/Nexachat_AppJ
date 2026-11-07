package com.nexachat.data;

import java.util.Objects;

/**
 * Reaction chip under a bubble, aggregated from {@code message_reactions}.
 * {@code reactedByMe} is resolved at ingest from the explicit value or the
 * current user's membership in the summary user ids.
 */
public final class ReactionChip {
    private final String emoji;
    private final int count;
    private final boolean reactedByMe;

    public ReactionChip(String emoji, int count, boolean reactedByMe) {
        this.emoji = Objects.requireNonNull(emoji, "emoji");
        this.count = count;
        this.reactedByMe = reactedByMe;
    }

    public String getEmoji() { return emoji; }
    public int getCount() { return count; }
    public boolean getReactedByMe() { return reactedByMe; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ReactionChip that)) return false;
        return count == that.count && reactedByMe == that.reactedByMe && emoji.equals(that.emoji);
    }
    @Override public int hashCode() { return Objects.hash(emoji, count, reactedByMe); }
}
