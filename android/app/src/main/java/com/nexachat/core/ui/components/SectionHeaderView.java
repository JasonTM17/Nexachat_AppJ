package com.nexachat.core.ui.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.nexachat.R;
import java.util.Locale;

/**
 * Uppercase section label for grouped Android View lists.
 */
public class SectionHeaderView extends FrameLayout {

    private final TextView titleView;

    public SectionHeaderView(Context context) {
        this(context, null);
    }

    public SectionHeaderView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        titleView = new TextView(context);
        int h = getResources().getDimensionPixelSize(R.dimen.space_md);
        int v = getResources().getDimensionPixelSize(R.dimen.space_sm);
        titleView.setPadding(h, v, h, v);
        titleView.setTextAppearance(R.style.TextAppearance_NexaChat_LabelMedium);
        titleView.setTextColor(ContextCompat.getColor(context, R.color.colorPrimary));
        addView(titleView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.START));
    }

    public void setTitle(CharSequence title) {
        titleView.setText(title == null ? "" : title.toString().toUpperCase(Locale.getDefault()));
    }
}
