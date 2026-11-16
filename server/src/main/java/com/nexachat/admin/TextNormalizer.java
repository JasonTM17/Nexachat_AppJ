package com.nexachat.admin;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Deterministic text folding for rule matching. Matching happens on two
 * normalized views of the same content:
 *
 * <ul>
 *   <li>{@link #fold} — Unicode NFD, combining marks stripped (so Vietnamese
 *       {@code tự tử} folds to {@code tu tu}), {@code đ→d}, lowercase,
 *       whitespace collapsed. Keyword rules match against this.</li>
 *   <li>{@link #leet} — additionally maps common leetspeak digits/symbols
 *       onto letters ({@code s3nd} → {@code send}) so simple obfuscation
 *       cannot dodge a keyword rule.</li>
 * </ul>
 *
 * <p>The folded key is never stored — only used at match time.
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /** Case/accent/whitespace fold. */
    public static String fold(String input) {
        if (input == null) {
            return "";
        }
        String nfd = Normalizer.normalize(input, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(nfd.length());
        boolean lastWasSpace = true;
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (c == 'đ') {
                c = 'd';
            } else if (c == 'Đ') {
                c = 'D';
            }
            if (Character.getType(c) == Character.NON_SPACING_MARK
                    || Character.getType(c) == Character.COMBINING_SPACING_MARK) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    out.append(' ');
                }
                lastWasSpace = true;
                continue;
            }
            out.append(Character.toLowerCase(c));
            lastWasSpace = false;
        }
        int length = out.length();
        if (length > 0 && out.charAt(length - 1) == ' ') {
            out.setLength(length - 1);
        }
        return out.toString();
    }

    /** Leetspeak view of an already-folded string. */
    public static String leet(String folded) {
        StringBuilder out = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            out.append(switch (c) {
                case '@', '4' -> 'a';
                case '3' -> 'e';
                case '1', '!', '|' -> 'i';
                case '0' -> 'o';
                case '5', '$' -> 's';
                case '7', '+' -> 't';
                case '8' -> 'b';
                case '9' -> 'g';
                case '2' -> 'z';
                case '6' -> 'g';
                default -> c;
            });
        }
        return out.toString();
    }

    /** Locale-stable lowercasing for one-off helpers. */
    static String lower(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT);
    }
}
