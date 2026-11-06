package com.nexachat.core.ui.components;

import com.nexachat.core.common.WhitespaceTrim;
import java.util.Locale;

/**
 * Deterministic avatar palette index rule shared by the Compose avatar and the native call view.
 */
public final class AvatarPalette {

    /** Must stay in sync with AvatarPaletteLight and AvatarPaletteDark in Avatar.kt. */
    private static final int PALETTE_SIZE = 8;

    private AvatarPalette() {
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
}
