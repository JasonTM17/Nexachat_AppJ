package com.nexachat.feature.encryption;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Presentation rules for the NC1 verification code: the canonical
 * {@code NC1} + 16 grouped-hex string is reflowed onto four lines of four
 * groups so the eye can track position while comparing out of band. The
 * single-line canonical form stays untouched for the clipboard.
 */
final class VerificationCodeText {

    private static final int GROUPS_PER_LINE = 4;

    private VerificationCodeText() { }

    /**
     * {@code "NC1 G1 G2 G3 G4\nG5 G6 G7 G8\n..."}. A value that does not look
     * like a grouped code is returned unchanged rather than mangled.
     */
    @NonNull
    static String lines(@Nullable String displayCode) {
        if (displayCode == null || !displayCode.startsWith("NC1 ")) {
            return displayCode == null ? "" : displayCode;
        }
        String body = displayCode.substring("NC1 ".length());
        String[] groups = body.split(" ");
        if (groups.length == 0) {
            return displayCode;
        }
        StringBuilder out = new StringBuilder("NC1 ");
        for (int i = 0; i < groups.length; i++) {
            if (i > 0) {
                out.append(i % GROUPS_PER_LINE == 0 ? '\n' : ' ');
            }
            out.append(groups[i]);
        }
        return out.toString();
    }
}
