package com.nexachat.core.media;

import androidx.annotation.Nullable;

/** Java whitespace checks that remain available on the app's minimum Android API. */
public final class MediaText {

    private MediaText() { }

    public static boolean isBlank(@Nullable CharSequence value) {
        if (value == null) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) {
                return false;
            }
        }
        return true;
    }
}
