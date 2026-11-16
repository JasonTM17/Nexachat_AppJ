package com.nexachat.feature.contacts;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure mapping rules for the contacts letter rail: which section letters the
 * grouped rows carry, where a letter's header sits in the adapter, which
 * letter a drag fraction lands on, and when the rail is worth showing.
 */
public final class ContactLetterIndex {

    /** The rail adds no value until at least this many sections exist. */
    static final int MIN_SECTIONS = 3;

    /**
     * Same threshold the rows use for large-type mode: above it the rail
     * hides so letters never overlap or clip row content.
     */
    static final float LARGE_FONT_SCALE = 1.3f;

    private ContactLetterIndex() {
    }

    /** Section letters in list order, one per header row — present letters only. */
    @NonNull
    public static List<String> letters(@NonNull List<ContactSections.Row> rows) {
        List<String> letters = new ArrayList<>();
        for (ContactSections.Row row : rows) {
            if (row instanceof ContactSections.Row.Header header) {
                letters.add(header.getLetter());
            }
        }
        return letters;
    }

    /**
     * Adapter position of the header row governing {@code letter}, or -1 when
     * the letter has no section (empty list, letter absent).
     */
    public static int adapterPosition(
            @NonNull List<ContactSections.Row> rows, @Nullable String letter) {
        if (letter == null) {
            return -1;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof ContactSections.Row.Header header
                    && header.getLetter().equals(letter)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Cell index under a vertical drag {@code fraction} (0 = top, 1 = bottom)
     * over {@code count} evenly sized cells. Clamps out-of-range fractions so
     * a finger leaving the rail keeps the first or last letter.
     */
    public static int indexAtFraction(int count, float fraction) {
        if (count <= 0) {
            return -1;
        }
        int index = (int) Math.floor(fraction * count);
        return Math.max(0, Math.min(count - 1, index));
    }

    /**
     * Whether the rail shows: enough sections to be a shortcut, and a font
     * scale where its letters still fit beside the rows. The list itself
     * stays fully scrollable either way.
     */
    public static boolean isRailVisible(int sectionCount, float fontScale) {
        return sectionCount >= MIN_SECTIONS && fontScale < LARGE_FONT_SCALE;
    }
}
