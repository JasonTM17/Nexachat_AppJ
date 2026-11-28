package com.nexachat.feature.feed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure text, media, and reaction rules for the public feed; no Android types so it stays testable. */
public final class FeedRules {

    /** Server limit for a post body, in characters after trimming. */
    public static final int MAX_POST_LENGTH = 1000;
    /** Server limit for a comment body, in characters after trimming. */
    public static final int MAX_COMMENT_LENGTH = 500;
    /** Server limit for keyword search query, in characters after trimming. */
    public static final int MAX_SEARCH_LENGTH = 100;
    /** Server ceiling for feed MP4 video uploads (4 MiB). */
    public static final long MAX_VIDEO_BYTES = 4L * 1024L * 1024L;
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

    /** Trimmed keyword search query capped at {@link #MAX_SEARCH_LENGTH}, or null when blank. */
    @Nullable
    public static String normalizeSearchQuery(@Nullable String raw) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return null;
        }
        return normalized.length() <= MAX_SEARCH_LENGTH
                ? normalized
                : normalized.substring(0, MAX_SEARCH_LENGTH);
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
        return canSubmitPost(body, hasImage, false);
    }

    /**
     * A text-only or video post requires a non-blank body within {@link #MAX_POST_LENGTH}.
     * An image post may omit the body, or include one up to {@link #MAX_POST_LENGTH}.
     */
    public static boolean canSubmitPost(@Nullable String body, boolean hasImage, boolean hasVideo) {
        String normalized = normalize(body);
        if (normalized == null) {
            return hasImage && !hasVideo;
        }
        return normalized.length() <= MAX_POST_LENGTH;
    }

    /** Editing a post via PATCH always requires a non-blank body within {@link #MAX_POST_LENGTH}. */
    public static boolean canSubmitEditedPost(@Nullable String body) {
        String normalized = normalize(body);
        return normalized != null && normalized.length() <= MAX_POST_LENGTH;
    }

    public static boolean canSubmitComment(@Nullable String body) {
        String normalized = normalize(body);
        return normalized != null && normalized.length() <= MAX_COMMENT_LENGTH;
    }

    /**
     * Vertical inset on each side that lets a pill of {@code pillHeightPx} tap in a
     * {@code touchTargetPx} high area while only the pill itself is drawn.
     */
    public static int reactionPillInset(int touchTargetPx, int pillHeightPx) {
        return Math.max(0, (touchTargetPx - pillHeightPx) / 2);
    }

    /** True when the server rejected the call because the caller is posting too fast. */
    public static boolean isRateLimited(@Nullable Throwable error) {
        return error instanceof FeedApiException api && api.isRateLimited();
    }

    /** True when the server disabled the feature (e.g. {@code videoUploadsEnabled=false}). */
    public static boolean isSocialFeatureDisabled(@Nullable Throwable error) {
        return error instanceof FeedApiException api
                && (api.getStatus() == 403 && "SOCIAL_FEATURE_DISABLED".equals(api.getCode()));
    }

    public static boolean isFileTooLarge(@Nullable Throwable error) {
        return error instanceof FeedApiException api
                && (api.getStatus() == 413 || "FILE_TOO_LARGE".equals(api.getCode()));
    }

    public static boolean isMimeNotAllowed(@Nullable Throwable error) {
        return error instanceof FeedApiException api
                && (api.getStatus() == 415 || "MIME_NOT_ALLOWED".equals(api.getCode()));
    }

    public static boolean isMp4Mime(@Nullable String mime) {
        if (mime == null) {
            return false;
        }
        int semicolon = mime.indexOf(';');
        String base = semicolon >= 0 ? mime.substring(0, semicolon) : mime;
        String normalized = base.trim().toLowerCase(Locale.ROOT);
        return "video/mp4".equals(normalized) || "application/mp4".equals(normalized);
    }

    /** Structural sanity check that bytes start with an ISO-BMFF {@code ftyp} box. */
    public static boolean hasIsoBmffFtypBox(@Nullable byte[] bytes) {
        return bytes != null
                && bytes.length >= 12
                && bytes[4] == 'f'
                && bytes[5] == 't'
                && bytes[6] == 'y'
                && bytes[7] == 'p';
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
