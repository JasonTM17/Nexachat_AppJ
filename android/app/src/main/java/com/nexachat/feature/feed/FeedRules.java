package com.nexachat.feature.feed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Pure text rules for composing a post or a comment; no Android types so it stays testable. */
public final class FeedRules {

    /** Server limit for a post body, in characters after trimming. */
    public static final int MAX_POST_LENGTH = 1000;
    /** Server limit for a comment body, in characters after trimming. */
    public static final int MAX_COMMENT_LENGTH = 500;
    public static final int POST_PAGE_SIZE = 20;
    public static final int COMMENT_PAGE_SIZE = 30;

    private FeedRules() { }

    /** The body the server will store: trimmed, or null when nothing is left. */
    @Nullable
    public static String normalize(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Characters that count against the limit; the server trims before measuring. */
    public static int length(@Nullable String raw) {
        String normalized = normalize(raw);
        return normalized == null ? 0 : normalized.length();
    }

    public static boolean isOverLimit(@Nullable String raw, int max) {
        return length(raw) > max;
    }

    /** A post needs text or an image, and the text must fit. */
    public static boolean canSubmitPost(@Nullable String body, boolean hasImage) {
        String normalized = normalize(body);
        if (normalized == null) {
            return hasImage;
        }
        return normalized.length() <= MAX_POST_LENGTH;
    }

    public static boolean canSubmitComment(@Nullable String body) {
        String normalized = normalize(body);
        return normalized != null && normalized.length() <= MAX_COMMENT_LENGTH;
    }

    /** True when the server rejected the call because the caller is posting too fast. */
    public static boolean isRateLimited(@Nullable Throwable error) {
        return error instanceof FeedApiException api && api.isRateLimited();
    }

    public static boolean hasReaction(
            @NonNull List<FeedRepository.Reaction> reactions, @NonNull String emoji) {
        for (FeedRepository.Reaction reaction : reactions) {
            if (emoji.equals(reaction.emoji())) {
                return reaction.mine();
            }
        }
        return false;
    }

    /**
     * The tally after the caller adds or removes one reaction, in the order the server uses:
     * existing emoji keep their slot, a new one is appended, and a tally that reaches zero drops
     * out. The server's answer replaces this as soon as it arrives.
     */
    @NonNull
    public static List<FeedRepository.Reaction> toggle(
            @NonNull List<FeedRepository.Reaction> current,
            @NonNull String emoji,
            boolean active) {
        List<FeedRepository.Reaction> next = new ArrayList<>(current.size() + 1);
        boolean found = false;
        for (FeedRepository.Reaction reaction : current) {
            if (!emoji.equals(reaction.emoji())) {
                next.add(reaction);
                continue;
            }
            found = true;
            int count = reaction.count() + (active ? 1 : -1);
            if (count > 0) {
                next.add(new FeedRepository.Reaction(emoji, count, active));
            }
        }
        if (!found && active) {
            next.add(new FeedRepository.Reaction(emoji, 1, true));
        }
        return List.copyOf(next);
    }
}
