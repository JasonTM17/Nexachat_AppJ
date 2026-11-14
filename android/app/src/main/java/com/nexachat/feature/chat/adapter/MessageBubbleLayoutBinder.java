package com.nexachat.feature.chat.adapter;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Guideline;
import com.nexachat.R;

/**
 * Applies {@link MessageBubbleLayoutRules} after measurement and again when the
 * row's width or configuration changes. Rows without the guideline pair are skipped.
 */
public final class MessageBubbleLayoutBinder {

    public void bind(@NonNull View root) {
        Object previous = root.getTag(R.id.bubbleStartLimit);
        if (previous instanceof View.OnLayoutChangeListener listener) {
            root.removeOnLayoutChangeListener(listener);
            root.setTag(R.id.bubbleStartLimit, null);
        }
        View startView = root.findViewById(R.id.bubbleStartLimit);
        View endView = root.findViewById(R.id.bubbleEndLimit);
        if (!(startView instanceof Guideline) || !(endView instanceof Guideline)) {
            return;
        }
        Guideline start = (Guideline) startView;
        Guideline end = (Guideline) endView;
        View.OnLayoutChangeListener listener = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) {
                // Guideline setters request another layout, so apply them outside this layout pass.
                view.post(() -> apply(view, start, end));
            }
        };
        root.setTag(R.id.bubbleStartLimit, listener);
        root.addOnLayoutChangeListener(listener);
        if (root.isLaidOut()) {
            root.post(() -> apply(root, start, end));
        }
    }

    private static void apply(@NonNull View root, @NonNull Guideline start, @NonNull Guideline end) {
        int innerWidth = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        float density = root.getResources().getDisplayMetrics().density;
        float fontScale = root.getResources().getConfiguration().fontScale;
        int maxWidth = MessageBubbleLayoutRules.maxBubbleWidthPx(innerWidth, fontScale, density);
        if (maxWidth > 0) {
            updatePercent(start, MessageBubbleLayoutRules.startGuidePercent(maxWidth, innerWidth));
            updatePercent(end, MessageBubbleLayoutRules.endGuidePercent(maxWidth, innerWidth));
        }
    }

    private static void updatePercent(Guideline guideline, float percent) {
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) guideline.getLayoutParams();
        if (params.guidePercent != percent) {
            guideline.setGuidelinePercent(percent);
        }
    }
}
