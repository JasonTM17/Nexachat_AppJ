package com.nexachat.core.ui.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.google.android.material.button.MaterialButton;
import com.nexachat.R;

/**
 * Full-surface error placeholder with optional retry (View counterpart to Compose {@code ErrorState}).
 */
public class ErrorStateView extends FrameLayout {

    private final TextView messageView;
    private final MaterialButton retryButton;

    public ErrorStateView(Context context) {
        this(context, null);
    }

    public ErrorStateView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int pad = getResources().getDimensionPixelSize(R.dimen.space_lg);
        column.setPadding(pad, pad, pad, pad);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(context);
        int iconSize = getResources().getDimensionPixelSize(R.dimen.min_touch_target);
        icon.setImageResource(R.drawable.ic_warning);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        column.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        messageView = new TextView(context);
        messageView.setGravity(Gravity.CENTER);
        column.addView(messageView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        retryButton = new MaterialButton(context);
        retryButton.setText(R.string.action_retry);
        retryButton.setVisibility(GONE);
        retryButton.setMinHeight(getResources().getDimensionPixelSize(R.dimen.min_touch_target));
        column.addView(retryButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    public void setMessage(CharSequence message) {
        messageView.setText(message);
    }

    public void setOnRetry(@Nullable Runnable onRetry) {
        if (onRetry == null) {
            retryButton.setVisibility(GONE);
            retryButton.setOnClickListener(null);
        } else {
            retryButton.setVisibility(VISIBLE);
            retryButton.setOnClickListener(v -> onRetry.run());
        }
    }
}
