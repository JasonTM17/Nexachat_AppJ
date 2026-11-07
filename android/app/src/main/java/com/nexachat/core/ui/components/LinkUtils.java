package com.nexachat.core.ui.components;

import com.nexachat.core.common.WhitespaceTrim;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure UI presentation rules for http(s) links inside message text.
 * Rejects javascript:, file:, content:, intent:, data:, and other schemes.
 */
public final class LinkUtils {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[^\\s<>\"'{}|\\\\^`]+)",
            Pattern.CASE_INSENSITIVE);

    private LinkUtils() {
    }

    /** Inclusive integer range [first, last]. */
    public static final class IntRange {
        private final int first;
        private final int last;

        public IntRange(int first, int last) {
            this.first = first;
            this.last = last;
        }

        public int getFirst() {
            return first;
        }

        public int getLast() {
            return last;
        }
    }

    /** Immutable 2-tuple. */
    public static final class Pair<A, B> {
        private final A first;
        private final B second;

        public Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }

        public A getFirst() {
            return first;
        }

        public B getSecond() {
            return second;
        }
    }

    /** Returns the trimmed URL when the scheme is http or https, otherwise null. */
    public static String sanitizeLinkUrl(String url) {
        String trimmed = WhitespaceTrim.trim(url);
        if (trimmed.isEmpty()) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        try {
            String scheme = new URI(trimmed).getScheme();
            if (scheme != null) {
                String normalized = scheme.toLowerCase(Locale.ROOT);
                if ("http".equals(normalized) || "https".equals(normalized)) {
                    return trimmed;
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Extracts valid http(s) URLs with inclusive UTF-16 ranges.
     */
    public static List<Pair<IntRange, String>> extractHttpLinks(String text) {
        if (WhitespaceTrim.trim(text).isEmpty()) {
            return Collections.emptyList();
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        List<Pair<IntRange, String>> links = new ArrayList<>();
        while (matcher.find()) {
            String url = matcher.group();
            String sanitized = sanitizeLinkUrl(url);
            if (sanitized != null) {
                links.add(new Pair<>(new IntRange(matcher.start(), matcher.end() - 1), sanitized));
            }
        }
        return links;
    }
}
