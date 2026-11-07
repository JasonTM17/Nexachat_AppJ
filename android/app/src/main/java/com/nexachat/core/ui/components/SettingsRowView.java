package com.nexachat.core.ui.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import com.nexachat.R;

/**
 * Single settings list row with leading icon and chevron (View counterpart to Compose {@code SettingsRow}).
 */
public class SettingsRowView extends LinearLayout {

    private final ImageView iconView;
    private final TextView titleView;
    private final TextView valueView;
    private final ImageView chevronView;

    public SettingsRowView(Context context) {
        this(context, null);
    }

    public SettingsRowView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int min = getResources().getDimensionPixelSize(R.dimen.min_touch_target);
        setMinimumHeight(min);
        int padH = getResources().getDimensionPixelSize(R.dimen.space_md);
        int padV = getResources().getDimensionPixelSize(R.dimen.space_sm);
        setPadding(padH, padV, padH, padV);

        iconView = new ImageView(context);
        int iconBox = (int) (36 * getResources().getDisplayMetrics().density);
        LayoutParams iconLp = new LayoutParams(iconBox, iconBox);
        iconLp.setMarginEnd(getResources().getDimensionPixelSize(R.dimen.space_sm));
        iconView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(iconView, iconLp);

        titleView = new TextView(context);
        LayoutParams titleLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(titleView, titleLp);

        valueView = new TextView(context);
        valueView.setVisibility(GONE);
        addView(valueView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        chevronView = new ImageView(context);
        chevronView.setImageResource(R.drawable.ic_keyboard_arrow_right);
        chevronView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(chevronView, new LayoutParams(
                getResources().getDimensionPixelSize(R.dimen.space_lg),
                getResources().getDimensionPixelSize(R.dimen.space_lg)));
    }

    public void setTitle(CharSequence title) {
        titleView.setText(title);
    }

    public void setTrailingValue(@Nullable CharSequence value) {
        if (value == null || value.length() == 0) {
            valueView.setVisibility(GONE);
        } else {
            valueView.setText(value);
            valueView.setVisibility(VISIBLE);
        }
    }

    public void setLeadingIcon(@DrawableRes int iconRes) {
        iconView.setImageResource(iconRes);
    }

    public void setShowChevron(boolean show) {
        chevronView.setVisibility(show ? VISIBLE : GONE);
    }
}
