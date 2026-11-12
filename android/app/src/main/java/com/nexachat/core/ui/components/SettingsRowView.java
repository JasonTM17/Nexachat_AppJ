package com.nexachat.core.ui.components;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;
import com.nexachat.R;

/**
 * Single settings list row with a leading icon tile and chevron for Android XML layouts.
 * Reuses view_settings_row layout with Material 3 styling and destructive mode support.
 */
public class SettingsRowView extends LinearLayout {

    private final FrameLayout iconTileView;
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView valueView;
    private final ImageView chevronView;

    public SettingsRowView(Context context) {
        this(context, null);
    }

    public SettingsRowView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_settings_row, this, true);

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
                valueView.setMaxWidth(
                        (int) TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP, 140, getResources().getDisplayMetrics()));
                valueView.setMaxLines(1);
                valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                valueView.setText(value);
                valueView.setVisibility(VISIBLE);
            }
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
        }
    }

    public void setShowChevron(boolean show) {
        if (chevronView != null) {
            chevronView.setVisibility(show ? VISIBLE : GONE);
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
}
