package com.nexachat.archive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts the http(s) URLs embedded in one TEXT message.
 *
 * <p>The rules are part of the storage-browser contract:
 * <ul>
 *   <li>only {@code http://} and {@code https://} (case-insensitive) qualify —
 *       {@code javascript:}, {@code data:} and other schemes never match;</li>
 *   <li>URLs are whitespace/control/angle-bracket delimited and may contain
 *       Unicode path and host characters;</li>
 *   <li>trailing sentence punctuation is removed, while a closing parenthesis
 *       is kept when it is balanced by an opening one (Wikipedia-style URLs);</li>
 *   <li>exact duplicates inside one message are de-duplicated and only the
 *       first {@value #MAX_LINKS} unique URLs are returned, preserving the
 *       order they appear in the text;</li>
 *   <li>nothing is fetched or resolved — this is pure text parsing.</li>
 * </ul>
 *
 * <p>The scan is linear in the message length and stops as soon as the cap is
 * reached, so a huge message cannot turn extraction into quadratic work.
 */
public final class ArchiveLinkExtractor {

    /** A single message contributes at most ten links to the archive. */
    public static final int MAX_LINKS = 10;

    private static final String HTTP = "http://";
    private static final String HTTPS = "https://";
    private static final Set<Character> TRAILING_PUNCTUATION =
            Set.of('.', ',', ';', ':', '!', '?', '\'', '"');

    private ArchiveLinkExtractor() {
        // Utility class: no instances.
    }

    /**
     * @param content message text; {@code null} yields an empty list
     * @return unique URLs in first-occurrence order, at most {@value #MAX_LINKS}
     */
    public static List<String> extract(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<String> links = new ArrayList<>(MAX_LINKS);
        Set<String> seen = new HashSet<>();
        int index = 0;
        while (index < content.length() && links.size() < MAX_LINKS) {
            int schemeStart = findScheme(content, index);
            if (schemeStart < 0) {
                break;
            }
            int urlEnd = schemeStart + schemeLength(content, schemeStart);
            while (urlEnd < content.length() && !isTerminator(content.charAt(urlEnd))) {
                urlEnd++;
            }
            String candidate = trimTrailing(content.substring(schemeStart, urlEnd));
            if (isValid(candidate) && seen.add(candidate)) {
                links.add(candidate);
            }
            index = Math.max(urlEnd, schemeStart + 1);
        }
        return List.copyOf(links);
    }

    private static int findScheme(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != 'h' && c != 'H') {
                continue;
            }
            if (!isPrecededByBoundary(text, i)) {
                continue;
            }
            if (startsWithIgnoreCase(text, i, HTTPS) || startsWithIgnoreCase(text, i, HTTP)) {
                return i;
            }
        }
        return -1;
    }

    /** A URL must not be the tail of a larger word such as {@code xhttps://…}. */
    private static boolean isPrecededByBoundary(String text, int index) {
        if (index == 0) {
            return true;
        }
        char previous = text.charAt(index - 1);
        return !Character.isLetterOrDigit(previous);
    }

    private static int schemeLength(String text, int schemeStart) {
        return startsWithIgnoreCase(text, schemeStart, HTTPS) ? HTTPS.length() : HTTP.length();
    }

    private static boolean startsWithIgnoreCase(String text, int offset, String expected) {
        if (offset + expected.length() > text.length()) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            char actual = text.charAt(offset + i);
            char wanted = expected.charAt(i);
            if (Character.toLowerCase(actual) != wanted) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTerminator(char c) {
        return Character.isWhitespace(c)
                || Character.isISOControl(c)
                || c == '<'
                || c == '>'
                || c == '"'
                || c == '`';
    }

    /**
     * Removes sentence punctuation glued to the end of a URL and closes only
     * unbalanced brackets. Balanced pairs are URL content and stay.
     */
    private static String trimTrailing(String value) {
        int end = value.length();
        while (end > 0) {
            char last = value.charAt(end - 1);
            if (TRAILING_PUNCTUATION.contains(last)) {
                end--;
                continue;
            }
            if (last == ')' && isUnbalanced(value, end, '(', ')')) {
                end--;
                continue;
            }
            if (last == ']' && isUnbalanced(value, end, '[', ']')) {
                end--;
                continue;
            }
            if (last == '}' && isUnbalanced(value, end, '{', '}')) {
                end--;
                continue;
            }
            break;
        }
        return value.substring(0, end);
    }

    private static boolean isUnbalanced(String value, int end, char open, char close) {
        int balance = 0;
        for (int i = 0; i < end; i++) {
            char c = value.charAt(i);
            if (c == open) {
                balance++;
            } else if (c == close) {
                balance--;
            }
        }
        return balance < 0;
    }

    /**
     * Structural sanity check: a scheme, a non-empty host with at least one
     * letter or digit, and no whitespace. Deliberately permissive — the server
     * does not resolve URLs and must not reject international hosts.
     */
    private static boolean isValid(String url) {
        int schemeEnd;
        if (startsWithIgnoreCase(url, 0, HTTPS)) {
            schemeEnd = HTTPS.length();
        } else if (startsWithIgnoreCase(url, 0, HTTP)) {
            schemeEnd = HTTP.length();
        } else {
            return false;
        }
        if (url.length() <= schemeEnd) {
            return false;
        }
        String remainder = url.substring(schemeEnd);
        int hostEnd = remainder.length();
        for (int i = 0; i < remainder.length(); i++) {
            char c = remainder.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                hostEnd = i;
                break;
            }
        }
        String authority = remainder.substring(0, hostEnd);
        if (authority.isEmpty()) {
            return false;
        }
        int at = authority.lastIndexOf('@');
        String host = at >= 0 ? authority.substring(at + 1) : authority;
        if (host.startsWith("[")) {
            int closing = host.indexOf(']');
            if (closing < 0) {
                return false;
            }
            host = host.substring(1, closing);
        } else {
            int portSeparator = host.lastIndexOf(':');
            if (portSeparator >= 0) {
                host = host.substring(0, portSeparator);
            }
        }
        if (host.isEmpty()) {
            return false;
        }
        boolean hasName = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (Character.isWhitespace(c)) {
                return false;
            }
            if (Character.isLetterOrDigit(c)) {
                hasName = true;
            }
        }
        return hasName;
    }
}
