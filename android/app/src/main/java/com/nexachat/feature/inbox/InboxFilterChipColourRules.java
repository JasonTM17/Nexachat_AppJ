package com.nexachat.feature.inbox;

import android.content.Context;
import android.content.res.ColorStateList;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import com.google.android.material.chip.Chip;
import com.nexachat.R;

/** Colour roles for inbox filter chips (checked tonal container and idle surface outline). */
public final class InboxFilterChipColourRules {

    public static final String CHECKED_CONTAINER = "colorPrimaryContainer";
    public static final String CHECKED_ON_CONTAINER = "colorOnPrimaryContainer";
    public static final String CHECKED_PRESSED_CONTAINER = "nexa_pressed_container";
    public static final String CHECKED_STROKE = "colorPrimary";
    public static final String IDLE_CONTAINER = "colorSurfaceContainer";
    public static final String IDLE_STROKE = "colorOutlineVariant";
    public static final String IDLE_TEXT = "colorOnSurfaceVariant";
    public static final float IDLE_STROKE_DP = 1f;

    private InboxFilterChipColourRules() {}

    public static void apply(@NonNull Chip chip, @NonNull Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int idleStrokePx = Math.round(IDLE_STROKE_DP * density);
        int[][] states = new int[][] {
            {android.R.attr.state_checked, android.R.attr.state_pressed},
            {android.R.attr.state_checked},
            {android.R.attr.state_pressed},
            {}
        };
        int[] fills = {
            color(context, R.color.nexa_pressed_container),
            color(context, R.color.colorPrimaryContainer),
            color(context, R.color.nexa_pressed_surface),
            color(context, R.color.colorSurfaceContainer)
        };
        chip.setChipBackgroundColor(new ColorStateList(states, fills));
        int[] labelColors = {
            color(context, R.color.colorOnPrimaryContainer),
            color(context, R.color.colorOnPrimaryContainer),
            color(context, R.color.colorOnSurfaceVariant),
            color(context, R.color.colorOnSurfaceVariant)
        };
        ColorStateList label = new ColorStateList(states, labelColors);
        chip.setTextColor(label);
        chip.setCheckedIconTint(label);
        int[] strokeColors = {
            color(context, R.color.colorPrimary),
            color(context, R.color.colorPrimary),
            color(context, R.color.colorOutlineVariant),
            color(context, R.color.colorOutlineVariant)
        };
        chip.setChipStrokeColor(new ColorStateList(states, strokeColors));
        chip.setChipStrokeWidth(idleStrokePx);
    }

    private static int color(@NonNull Context context, int resId) {
        return ContextCompat.getColor(context, resId);
    }
}
