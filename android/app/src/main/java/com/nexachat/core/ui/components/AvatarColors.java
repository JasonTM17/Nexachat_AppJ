package com.nexachat.core.ui.components;

import android.content.Context;
import android.content.res.Configuration;
import androidx.annotation.ColorInt;
import androidx.core.content.ContextCompat;
import com.nexachat.R;

/**
 * Deterministic avatar container/on colors — mirrors {@code AvatarPaletteLight/Dark} in Avatar.kt.
 */
public final class AvatarColors {

    private static final int[][] LIGHT = {
            {R.color.avatar_indigo_container, R.color.avatar_indigo_on},
            {R.color.avatar_blue_container, R.color.avatar_blue_on},
            {R.color.avatar_cyan_container, R.color.avatar_cyan_on},
            {R.color.avatar_teal_container, R.color.avatar_teal_on},
            {R.color.avatar_emerald_container, R.color.avatar_emerald_on},
            {R.color.avatar_amber_container, R.color.avatar_amber_on},
            {R.color.avatar_rose_container, R.color.avatar_rose_on},
            {R.color.avatar_purple_container, R.color.avatar_purple_on},
    };

    private static final int[][] DARK = {
            {R.color.avatar_indigo_container_dark, R.color.avatar_indigo_on_dark},
            {R.color.avatar_blue_container_dark, R.color.avatar_blue_on_dark},
            {R.color.avatar_cyan_container_dark, R.color.avatar_cyan_on_dark},
            {R.color.avatar_teal_container_dark, R.color.avatar_teal_on_dark},
            {R.color.avatar_emerald_container_dark, R.color.avatar_emerald_on_dark},
            {R.color.avatar_amber_container_dark, R.color.avatar_amber_on_dark},
            {R.color.avatar_rose_container_dark, R.color.avatar_rose_on_dark},
            {R.color.avatar_purple_container_dark, R.color.avatar_purple_on_dark},
    };

    private AvatarColors() {
    }

    @ColorInt
    public static int containerColor(Context context, String name) {
        int index = AvatarPalette.avatarPaletteIndex(name);
        int res = palette(context)[index][0];
        return ContextCompat.getColor(context, res);
    }

    @ColorInt
    public static int onContainerColor(Context context, String name) {
        int index = AvatarPalette.avatarPaletteIndex(name);
        int res = palette(context)[index][1];
        return ContextCompat.getColor(context, res);
    }

    private static int[][] palette(Context context) {
        int nightMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES ? DARK : LIGHT;
    }
}
