package com.nexachat.feature.chat.adapter;

import android.view.View;
import android.view.ViewGroup;
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
        View.OnLayoutChangeListener listener = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                apply(view, start, end);
        root.setTag(R.id.bubbleStartLimit, listener);
        root.addOnLayoutChangeListener(listener);
        apply(root, start, end);
    }

    private static void apply(@NonNull View root, @NonNull Guideline start, @NonNull Guideline end) {
        int innerWidth = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        float density = root.getResources().getDisplayMetrics().density;
        float fontScale = root.getResources().getConfiguration().fontScale;
        float[] guides = MessageBubbleLayoutRules.guidesOrNull(innerWidth, fontScale, density);
        if (guides == null) {
            return;
        }
        if (!MessageBubbleLayoutRules.differs(percentOf(start), guides[0])
                && !MessageBubbleLayoutRules.differs(percentOf(end), guides[1])) {
            return;
        }
        // A guideline change requests layout; issued from inside a layout pass it would be
        // dropped, leaving the row at its previous width until something else relayouts it.
        root.post(() -> {
            start.setGuidelinePercent(guides[0]);
            end.setGuidelinePercent(guides[1]);
        });
    }

    private static float percentOf(@NonNull Guideline guideline) {
        ViewGroup.LayoutParams params = guideline.getLayoutParams();
        return params instanceof ConstraintLayout.LayoutParams constraint ? constraint.guidePercent : -1f;
    }
}
