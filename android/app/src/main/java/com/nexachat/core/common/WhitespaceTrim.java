package com.nexachat.core.common;

/**
 * Trims the same characters as Kotlin {@code CharSequence.trim}: a code point
 * is whitespace when {@link Character#isWhitespace(int)} or
 * {@link Character#isSpaceChar(int)} is true. Java {@link String#trim()} and
 * {@link String#strip()} leave NBSP (U+00A0) and EM SPACE (U+2003) in place.
 */
public final class WhitespaceTrim {

    private WhitespaceTrim() {
    }

    public static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isKotlinWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (start < end && isKotlinWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        if (start == 0 && end == value.length()) {
            return value;
        }
        return value.substring(start, end);
    }

    static boolean isKotlinWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
