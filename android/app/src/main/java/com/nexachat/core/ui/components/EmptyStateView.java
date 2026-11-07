package com.nexachat.core.ui.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.google.android.material.button.MaterialButton;
import com.nexachat.R;

/**
 * Full-surface empty-state placeholder for Android XML layouts.
 */
public class EmptyStateView extends FrameLayout {

    private final ImageView iconView;
    private final TextView messageView;
    private final MaterialButton actionButton;

    public EmptyStateView(Context context) {
        this(context, null);
    }

    public EmptyStateView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int pad = getResources().getDimensionPixelSize(R.dimen.space_xl);
        column.setPadding(pad, pad, pad, pad);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(column, lp);

        iconView = new ImageView(context);
        int iconSize = (int) (72 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_md);
        iconView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        column.addView(iconView, iconLp);

        messageView = new TextView(context);
        messageView.setGravity(Gravity.CENTER);
        column.addView(messageView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        actionButton = new MaterialButton(context);
        actionButton.setVisibility(GONE);
        int min = getResources().getDimensionPixelSize(R.dimen.min_touch_target);
        actionButton.setMinHeight(min);
        column.addView(actionButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    public void setMessage(CharSequence message) {
        messageView.setText(message);
    }

    public void setIcon(@DrawableRes int iconRes) {
        iconView.setImageResource(iconRes);
        iconView.setVisibility(VISIBLE);
    }

    public void setAction(@StringRes int labelRes, Runnable onClick) {
        actionButton.setText(labelRes);
        actionButton.setVisibility(VISIBLE);
        actionButton.setOnClickListener(v -> onClick.run());
    }

    public void clearAction() {
        actionButton.setVisibility(GONE);
        actionButton.setOnClickListener(null);
    }
}
