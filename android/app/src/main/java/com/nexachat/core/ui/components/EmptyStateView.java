package com.nexachat.core.ui.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
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
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LayoutInflater.from(context).inflate(R.layout.view_empty_state, column, true);
        iconView = column.findViewById(R.id.emptyIcon);
        messageView = column.findViewById(R.id.emptyMessage);
        actionButton = column.findViewById(R.id.emptyAction);
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
