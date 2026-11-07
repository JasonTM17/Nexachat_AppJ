package com.nexachat.core.ui.components;

import com.nexachat.core.common.WhitespaceTrim;
import com.nexachat.core.ui.MessageUiRules;
import java.util.Locale;

/**
 * Deterministic avatar palette index and ARGB color pair rules shared by {@link AvatarView} and the native call view.
 */
public final class AvatarPalette {

    private static final int PALETTE_SIZE = 8;

    private static final int[][] LIGHT_ARGB = {
            {0xFFE0E7FF, 0xFF3730A3},
            {0xFFDBEAFE, 0xFF1E40AF},
            {0xFFCFFAFE, 0xFF155E75},
            {0xFFCCFBF1, 0xFF115E59},
            {0xFFD1FAE5, 0xFF065F46},
            {0xFFFEF3C7, 0xFF92400E},
            {0xFFFFE4E6, 0xFF9F1239},
            {0xFFF3E8FF, 0xFF6B21A8},
    };

    private static final int[][] DARK_ARGB = {
            {0xFF312E81, 0xFFE0E7FF},
            {0xFF1E3A8A, 0xFFDBEAFE},
            {0xFF164E63, 0xFFCFFAFE},
            {0xFF134E4A, 0xFFCCFBF1},
            {0xFF064E3B, 0xFFD1FAE5},
            {0xFF78350F, 0xFFFEF3C7},
            {0xFF881337, 0xFFFFE4E6},
            {0xFF581C87, 0xFFF3E8FF},
    };

    private AvatarPalette() {
    }

    /** ARGB container / on-container pair for avatar contrast verification. */
    public static final class ColorPair {
        private final int container;
        private final int onContainer;

        public ColorPair(int container, int onContainer) {
            this.container = container;
            this.onContainer = onContainer;
        }

        public int getContainer() {
            return container;
        }

        public int getOnContainer() {
            return onContainer;
        }
    }

    /**
     * Returns the deterministic palette index in the range 0..7 for the given name.
     * The name is trimmed and lowercased first, so case and surrounding whitespace
     * never change the index. Empty or null names return 0.
     */
    public static int avatarPaletteIndex(String name) {
        String trimmed = WhitespaceTrim.trim(name == null ? "" : name);
        if (trimmed.isEmpty()) {
            return 0;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        int hash = 0;
        for (int i = 0; i < normalized.length(); i++) {
            hash = hash * 31 + normalized.charAt(i);
        }
        return (hash & 0x7FFFFFFF) % PALETTE_SIZE;
    }

    public static String extractInitials(String name) {
        return MessageUiRules.extractInitials(name == null ? "" : name);
    }

    public static ColorPair avatarColorsForName(String name, boolean darkTheme) {
        int index = avatarPaletteIndex(name);
        int[][] table = darkTheme ? DARK_ARGB : LIGHT_ARGB;
        return new ColorPair(table[index][0], table[index][1]);
    }
}
