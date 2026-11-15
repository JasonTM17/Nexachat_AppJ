package com.nexachat.feature.inbox;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import com.google.android.material.chip.Chip;
import com.nexachat.R;

/**
 * Colour roles for inbox, label and summary filter chips. Mirrors the
 * {@code nexa_filter_chip_*} selectors so runtime-applied chips match the
 * tonal Tonal Plum contract in both themes.
 */
public final class InboxFilterChipColourRules {

    public static final String CHECKED_FILL = "colorPrimaryContainer";
    public static final String CHECKED_ON_FILL = "colorOnPrimaryContainer";
    public static final String CHECKED_PRESSED_FILL = "nexa_pressed_container";
    public static final String CHECKED_STROKE = "colorPrimary";
    public static final String IDLE_FILL = "colorSurfaceContainer";
    public static final String IDLE_PRESSED_FILL = "nexa_pressed_surface";
    public static final String IDLE_STROKE = "colorOutlineVariant";
    public static final String IDLE_TEXT = "colorOnSurfaceVariant";
    public static final String DISABLED_FILL = "nexa_disabled_container";
    public static final String DISABLED_ON = "nexa_on_disabled";
    public static final float IDLE_STROKE_DP = 1f;

    private InboxFilterChipColourRules() {}

    public static void apply(@NonNull Chip chip, @NonNull Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int idleStrokePx = Math.round(IDLE_STROKE_DP * density);
        int[][] states = new int[][] {
            {-android.R.attr.state_enabled},
            {android.R.attr.state_checked, android.R.attr.state_pressed},
            {android.R.attr.state_checked},
            {android.R.attr.state_pressed},
            {}
        };
        int[] fills = {
            color(context, R.color.nexa_disabled_container),
            color(context, R.color.nexa_pressed_container),
            themeColor(context, com.google.android.material.R.attr.colorPrimaryContainer),
            color(context, R.color.nexa_pressed_surface),
            themeColor(context, com.google.android.material.R.attr.colorSurfaceContainer)
        };
        chip.setChipBackgroundColor(new ColorStateList(states, fills));
        int onContainer = themeColor(
                context, com.google.android.material.R.attr.colorOnPrimaryContainer);
        int onSurfaceVariant = themeColor(
                context, com.google.android.material.R.attr.colorOnSurfaceVariant);
        int[] labelColors = {
            color(context, R.color.nexa_on_disabled),
            onContainer,
            onContainer,
            onSurfaceVariant,
            onSurfaceVariant
        };
        ColorStateList label = new ColorStateList(states, labelColors);
        chip.setTextColor(label);
        chip.setCheckedIconTint(label);
        int primary = themeColor(context, com.google.android.material.R.attr.colorPrimary);
        int outlineVariant = themeColor(
                context, com.google.android.material.R.attr.colorOutlineVariant);
        int[] strokeColors = {
            color(context, R.color.nexa_on_disabled),
            primary,
            primary,
            outlineVariant,
            outlineVariant
        };
        chip.setChipStrokeColor(new ColorStateList(states, strokeColors));
        chip.setChipStrokeWidth(idleStrokePx);
        applyLabelWeight(chip, chip.isChecked());
        chip.setOnCheckedChangeListener(
                (view, checked) -> applyLabelWeight((Chip) view, checked));
    }

    /** The spec renders the selected label bold; keep the family, toggle only the style. */
    private static void applyLabelWeight(@NonNull Chip chip, boolean checked) {
        Typeface resolved = chip.getTypeface();
        if (resolved == null) {
            resolved = ResourcesCompat.getFont(chip.getContext(), R.font.inter);
        }
        int style = checked ? Typeface.BOLD : Typeface.NORMAL;
        chip.setTypeface(Typeface.create(resolved, style), style);
    }

    private static int themeColor(@NonNull Context context, int attr) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) {
            return 0;
        }
        if (value.resourceId != 0) {
            return ContextCompat.getColor(context, value.resourceId);
        }
        return value.data;
    }

    private static int color(@NonNull Context context, int resId) {
        return ContextCompat.getColor(context, resId);
    }
}
