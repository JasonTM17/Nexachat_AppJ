package com.nexachat.core.ui.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.nexachat.R;

/**
 * Centered loading spinner with optional label (View counterpart to Compose {@code LoadingState}).
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

        CircularProgressIndicator spinner = new CircularProgressIndicator(context);
        spinner.setIndeterminate(true);
        column.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        labelView = new TextView(context);
        labelView.setVisibility(GONE);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = getResources().getDimensionPixelSize(R.dimen.space_sm);
        column.addView(labelView, labelLp);
    }

    public void setLabel(@Nullable CharSequence label) {
        if (label == null || label.length() == 0) {
            labelView.setVisibility(GONE);
        } else {
            labelView.setText(label);
            labelView.setVisibility(VISIBLE);
        }
    }
}
