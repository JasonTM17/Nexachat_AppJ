package com.nexachat.core.network.dto;

/** Kotlin {@code CharSequence.isBlank} / {@code trim} whitespace, including NBSP. */
final class JsonText {
    private JsonText() {
    }

    static boolean isBlank(String value) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }
}
