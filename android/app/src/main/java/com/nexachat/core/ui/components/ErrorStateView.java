package com.nexachat.core.ui.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.google.android.material.button.MaterialButton;
import com.nexachat.R;

/**
 * Full-surface error placeholder with an optional retry action.
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

        LayoutInflater.from(context).inflate(R.layout.view_error_state, column, true);
        messageView = column.findViewById(R.id.errorMessage);
        retryButton = column.findViewById(R.id.retryButton);
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
