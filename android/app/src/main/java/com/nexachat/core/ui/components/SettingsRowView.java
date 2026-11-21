package com.nexachat.core.ui.components;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;
import com.google.android.material.color.MaterialColors;
import com.nexachat.R;

/**
 * Single settings list row with a leading icon tile and chevron for Android XML layouts.
 * Reuses view_settings_row layout with Material 3 styling and destructive mode support.
 */
public class SettingsRowView extends LinearLayout {

    private final LinearLayout rowContent;
    private final LinearLayout rowTextColumn;
    private final FrameLayout iconTileView;
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView valueView;
    private final ImageView chevronView;
    private boolean valueStacked;

    public SettingsRowView(Context context) {
        this(context, null);
    }

    public SettingsRowView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_settings_row, this, true);

        rowContent = findViewById(R.id.rowContent);
        rowTextColumn = findViewById(R.id.rowTextColumn);
        iconTileView = findViewById(R.id.rowIconTile);
        iconView = findViewById(R.id.rowIcon);
        titleView = findViewById(R.id.rowTitle);
        subtitleView = findViewById(R.id.rowSubtitle);
        applyTypeGrowth();
        valueView = findViewById(R.id.rowValue);
        chevronView = findViewById(R.id.rowChevron);

        if (iconTileView != null) {
            iconTileView.setVisibility(GONE);
        }
    }

    public void setTitle(CharSequence title) {
        if (titleView != null) {
            titleView.setText(title);
        }
        updateRowAccessibilityDescription();
    }

    public void setSubtitle(@Nullable CharSequence subtitle) {
        if (subtitleView != null) {
            if (subtitle == null || subtitle.length() == 0) {
                subtitleView.setVisibility(GONE);
            } else {
                subtitleView.setText(subtitle);
                subtitleView.setVisibility(VISIBLE);
            }
            applyTypeGrowth();
        }
        updateRowAccessibilityDescription();
    }

    /**
     * The row is one tap target: its own description merges the visible title,
     * subtitle and trailing value (the same composed-row pattern the inbox
     * conversation rows use since #260), so TalkBack and the a11y sweep's
     * flat dump both read one label per row instead of a clickable node whose
     * label only lives in child views (#254).
     */
    private void updateRowAccessibilityDescription() {
        if (titleView == null) {
            return;
        }
        java.util.List<String> parts = new java.util.ArrayList<>(3);
        addVisibleText(parts, titleView);
        addVisibleText(parts, subtitleView);
        addVisibleText(parts, valueView);
        setContentDescription(parts.isEmpty() ? null : String.join(", ", parts));
    }

    private static void addVisibleText(
            @NonNull java.util.List<String> parts, @Nullable TextView view) {
        if (view == null || view.getVisibility() != VISIBLE) {
            return;
        }
        CharSequence text = view.getText();
        if (text != null && text.length() > 0) {
            parts.add(text.toString());
        }
    }

    private void applyTypeGrowth() {
        boolean large = getResources().getConfiguration().fontScale >= 1.5f;
        if (titleView != null) {
            titleView.setMaxLines(large ? Integer.MAX_VALUE : 2);
            titleView.setEllipsize(large ? null : android.text.TextUtils.TruncateAt.END);
        }
        if (subtitleView != null) {
            subtitleView.setMaxLines(large ? Integer.MAX_VALUE : 2);
            subtitleView.setEllipsize(large ? null : android.text.TextUtils.TruncateAt.END);
        }
        applyMinHeight();
    }

    /** A subtitle or a stacked trailing value is a second text line. */
    private void applyMinHeight() {
        boolean twoLine = valueStacked
                || (subtitleView != null && subtitleView.getVisibility() == VISIBLE);
        int minHeight = getResources().getDimensionPixelSize(
                twoLine ? R.dimen.list_item_two_line_min_height : R.dimen.list_item_min_height);
        setMinimumHeight(minHeight);
        // The inner row carries the ripple and centres its content, so it must fill the height.
        rowContent.setMinimumHeight(minHeight);
    }

    public void setTrailingValue(@Nullable CharSequence value) {
        if (valueView != null) {
            if (value == null || value.length() == 0) {
                valueView.setVisibility(GONE);
            } else {
                valueView.setText(value);
                valueView.setVisibility(VISIBLE);
            }
            applyTrailingValueLayout(getWidth());
        }
        updateRowAccessibilityDescription();
    }

    public void setTrailingEmphasis(boolean emphasized) {
        if (valueView != null) {
            int colorAttr = emphasized
                    ? com.google.android.material.R.attr.colorPrimary
                    : com.google.android.material.R.attr.colorOnSurfaceVariant;
            valueView.setTextColor(MaterialColors.getColor(valueView, colorAttr));
            valueView.setMaxLines(
                    emphasized && getResources().getConfiguration().fontScale >= 1.3f ? 2 : 1);
            valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
    }

    public void setLeadingIcon(@DrawableRes int iconRes) {
        if (iconView != null && iconTileView != null) {
            if (iconRes != 0) {
                iconView.setImageResource(iconRes);
                iconTileView.setVisibility(VISIBLE);
            } else {
                iconTileView.setVisibility(GONE);
            }
            applyTrailingValueLayout(getWidth());
        }
    }

    public void setLeadingIconUntinted(@DrawableRes int iconRes) {
        if (iconView != null && iconTileView != null) {
            if (iconRes != 0) {
                iconView.setImageResource(iconRes);
                ImageViewCompat.setImageTintList(iconView, null);
                iconTileView.setVisibility(VISIBLE);
            } else {
                iconTileView.setVisibility(GONE);
            }
            applyTrailingValueLayout(getWidth());
        }
    }

    public void setShowChevron(boolean show) {
        if (chevronView != null) {
            chevronView.setVisibility(show ? VISIBLE : GONE);
            applyTrailingValueLayout(getWidth());
        }
    }

    public void setDestructive(boolean destructive) {
        if (!destructive) return;
        Context ctx = getContext();
        TypedValue errorColorVal = new TypedValue();
        ctx.getTheme().resolveAttribute(com.google.android.material.R.attr.colorError, errorColorVal, true);
        int errorColor = errorColorVal.data;

        if (titleView != null) {
            titleView.setTextColor(errorColor);
        }
        if (iconTileView != null) {
            iconTileView.setBackgroundResource(R.drawable.bg_settings_tile_error);
        }
        if (iconView != null) {
            ImageViewCompat.setImageTintList(iconView, ColorStateList.valueOf(errorColor));
        }
        if (chevronView != null) {
            ImageViewCompat.setImageTintList(chevronView, ColorStateList.valueOf(errorColor));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Reparenting the value inside a layout pass leaves it laid out below
        // the parent's already-measured bounds (clipped and absent from a11y);
        // defer the placement until the pass completes so the next measure
        // sees the child in its final parent.
        post(() -> applyTrailingValueLayout(w));
    }

    private void applyTrailingValueLayout(int rowWidthPx) {
        if (valueView == null || rowContent == null || rowTextColumn == null) {
            return;
        }
        boolean valueVisible = valueView.getVisibility() == VISIBLE;
        boolean chevronVisible = chevronView != null && chevronView.getVisibility() == VISIBLE;
        boolean leadingVisible = iconTileView != null && iconTileView.getVisibility() == VISIBLE;
        boolean stack = SettingsRowLayoutRules.shouldStackTrailingValue(
                getResources().getDisplayMetrics().density,
                rowWidthPx > 0 ? rowWidthPx : getWidth(),
                valueVisible,
                chevronVisible,
                leadingVisible,
                titleTextWidthPx());
        if (stack == valueStacked) {
            if (!stack && valueVisible) {
                applyInlineValueStyle();
            } else if (stack && valueVisible) {
                applyStackedValueStyle();
            }
            return;
        }
        valueStacked = stack;
        ViewGroup currentParent = (ViewGroup) valueView.getParent();
        if (stack) {
            if (currentParent != rowTextColumn) {
                if (currentParent != null) {
                    currentParent.removeView(valueView);
                }
                rowTextColumn.addView(valueView, stackedLayoutParams());
            }
            applyStackedValueStyle();
        } else {
            if (currentParent != rowContent) {
                if (currentParent != null) {
                    currentParent.removeView(valueView);
                }
                int chevronIndex = rowContent.indexOfChild(chevronView);
                rowContent.addView(
                        valueView, chevronIndex >= 0 ? chevronIndex : rowContent.getChildCount(),
                        inlineLayoutParams());
            }
            applyInlineValueStyle();
        }
        // This may still run inside a layout pass via a setter; a direct
        // requestLayout there is dropped, so defer it to the next pass.
        post(this::requestLayout);
    }

    private float titleTextWidthPx() {
        if (titleView == null || titleView.getText() == null) {
            return 0f;
        }
        return titleView.getPaint().measureText(titleView.getText().toString());
    }

    private LinearLayout.LayoutParams inlineLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(getResources().getDimensionPixelSize(R.dimen.space_sm));
        lp.setMarginEnd(getResources().getDimensionPixelSize(R.dimen.space_xs));
        return lp;
    }

    private LinearLayout.LayoutParams stackedLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = getResources().getDimensionPixelSize(R.dimen.space_xxs);
        return lp;
    }

    private void applyInlineValueStyle() {
        applyMinHeight();
        valueView.setMaxWidth(getResources().getDimensionPixelSize(R.dimen.value_max_width));
        valueView.setMaxLines(1);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    private void applyStackedValueStyle() {
        applyMinHeight();
        valueView.setMaxWidth(Integer.MAX_VALUE);
        valueView.setMaxLines(2);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }
}
