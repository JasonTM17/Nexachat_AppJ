package com.nexachat.core.common;

/**
 * Trims a code point when {@link Character#isWhitespace(int)} or
 * {@link Character#isSpaceChar(int)} returns true. Java {@link String#trim()}
 * and {@link String#strip()} leave NBSP (U+00A0) and EM SPACE (U+2003) in place.
 */
public final class WhitespaceTrim {

    private WhitespaceTrim() {
    }

    public static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isUnicodeWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (start < end && isUnicodeWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        if (start == 0 && end == value.length()) {
            return value;
        }
        return value.substring(start, end);
    }

    static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
