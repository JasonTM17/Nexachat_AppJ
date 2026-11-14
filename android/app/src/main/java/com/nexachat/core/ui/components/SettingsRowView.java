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
        int minDp = subtitleView != null && subtitleView.getVisibility() == VISIBLE ? 80 : 64;
        setMinimumHeight(Math.round(minDp * getResources().getDisplayMetrics().density));
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
        applyTrailingValueLayout(w);
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
        // This runs from onSizeChanged, inside a layout pass, where a direct requestLayout is dropped.
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
        int gap = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        lp.setMarginStart(gap);
        lp.setMarginEnd((int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics()));
        return lp;
    }

    private LinearLayout.LayoutParams stackedLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 2, getResources().getDisplayMetrics());
        return lp;
    }

    private void applyInlineValueStyle() {
        valueView.setMaxWidth((int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 140, getResources().getDisplayMetrics()));
        valueView.setMaxLines(1);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    private void applyStackedValueStyle() {
        valueView.setMaxWidth(Integer.MAX_VALUE);
        valueView.setMaxLines(2);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }
}
