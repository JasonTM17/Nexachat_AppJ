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
 * <p>Folding shrinks the text: whitespace runs collapse to one space and
 * leading/trailing whitespace is trimmed, so a match found in the folded view
 * does not sit at the same index as in the original. Use {@link #foldView}
 * when the caller reports match offsets against the original text.</p>
 *
 * <p>The folded key is never stored — only used at match time.</p>
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /** Folded text plus, for every folded char, the original index it came from. */
    public record FoldedView(String text, int[] originalOffsets) {

        /** Original-text index of the folded char at {@code foldedOffset}. */
        public int originalOffset(int foldedOffset) {
            return originalOffsets[foldedOffset];
        }

        /**
         * Exclusive original-text end of the folded range
         * {@code [foldedOffset, foldedOffset + foldedLength)}. The end is the
         * original index of the folded char that follows the range, or the
         * original length when the range reaches the folded end.
         */
        public int originalEnd(int foldedOffset, int foldedLength) {
            return originalOffsets[foldedOffset + foldedLength];
        }

        /** Original-text span length of a folded range. */
        public int originalLength(int foldedOffset, int foldedLength) {
            return originalEnd(foldedOffset, foldedLength) - originalOffsets[foldedOffset];
        }
    }

    /** Case/accent/whitespace fold. */
    public static String fold(String input) {
        return foldView(input).text();
    }

    /**
     * The same fold as {@link #fold}, with an index map back to the original
     * text: entry {@code i} names the original index the {@code i}-th folded
     * char came from, and one trailing sentinel entry names the original
     * length. The mapping makes folded match positions translatable to the
     * original text a caller slices evidence from.
     */
    public static FoldedView foldView(String input) {
        if (input == null || input.isEmpty()) {
            return new FoldedView("", new int[]{0});
        }
        int length = input.length();
        // Decompose one original char at a time so every NFD position can be
        // attributed to its original index. Canonical decomposition is
        // code-point-local, so this equals normalizing the whole string.
        StringBuilder nfd = new StringBuilder(length + 8);
        int[] nfdOriginal = new int[Math.max(8, length * 3)];
        int nfdLength = 0;
        for (int originalIndex = 0; originalIndex < length; originalIndex++) {
            String decomposed = decompose(input.charAt(originalIndex));
            for (int position = 0; position < decomposed.length(); position++) {
                if (nfdLength == nfdOriginal.length) {
                    nfdOriginal = java.util.Arrays.copyOf(nfdOriginal, nfdOriginal.length * 2);
                }
                nfdOriginal[nfdLength++] = originalIndex;
            }
            nfd.append(decomposed);
        }

        StringBuilder out = new StringBuilder(nfdLength);
        int[] offsets = new int[nfdLength + 1];
        boolean lastWasSpace = true;
        int foldedLength = 0;
        for (int i = 0; i < nfdLength; i++) {
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
                    offsets[foldedLength++] = nfdOriginal[i];
                }
                lastWasSpace = true;
                continue;
            }
            out.append(Character.toLowerCase(c));
            offsets[foldedLength++] = nfdOriginal[i];
            lastWasSpace = false;
        }
        int outLength = out.length();
        if (outLength > 0 && out.charAt(outLength - 1) == ' ') {
            out.setLength(outLength - 1);
            foldedLength--;
        }
        offsets[foldedLength] = length;
        return new FoldedView(out.toString(), java.util.Arrays.copyOf(offsets, foldedLength + 1));
    }

    /** NFD of one char; the fast path avoids the normalizer for ranges with no decomposition. */
    private static String decompose(char c) {
        if (c < 0xC0) {
            return String.valueOf(c);
        }
        return Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
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
