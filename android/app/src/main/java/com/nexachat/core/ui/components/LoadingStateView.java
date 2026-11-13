package com.nexachat.core.ui.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.nexachat.R;

/**
 * Centered loading spinner with an optional label for Android XML layouts.
 */
public class LoadingStateView extends FrameLayout {

    private final TextView labelView;

    public LoadingStateView(Context context) {
        this(context, null);
    }

    public LoadingStateView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LayoutInflater.from(context).inflate(R.layout.view_loading_state, column, true);
        labelView = column.findViewById(R.id.loadingLabel);
    }

    public void setLabel(@Nullable CharSequence label) {
        if (label == null || label.length() == 0) {
            labelView.setText(R.string.loading_status);
            labelView.setVisibility(VISIBLE);
        } else {
            labelView.setText(label);
            labelView.setVisibility(VISIBLE);
        }
    }
}
