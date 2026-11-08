package com.nexachat.feature.chat.adapter;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;

/** Image button that fades between visible and hidden composer actions. */
public final class ChatActionImageButton extends AppCompatImageButton {

    private static final long CROSS_FADE_DURATION_MS = 150L;
    private static final int NO_PENDING_VISIBILITY = -1;

    private int pendingVisibility = NO_PENDING_VISIBILITY;

    public ChatActionImageButton(@NonNull Context context) {
        super(context);
    }

    public ChatActionImageButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ChatActionImageButton(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setVisibility(int visibility) {
        if (!isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            animate().cancel();
            pendingVisibility = NO_PENDING_VISIBILITY;
            super.setVisibility(visibility);
            setAlpha(1.0f);
            setClickable(visibility == View.VISIBLE);
            setImportantForAccessibility(visibility == View.VISIBLE
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return;
        }

        if (visibility == View.VISIBLE) {
            showWithCrossFade();
        } else if (visibility == View.GONE) {
            hideWithCrossFade();
        } else {
            animate().cancel();
            pendingVisibility = NO_PENDING_VISIBILITY;
            super.setVisibility(visibility);
            setAlpha(1.0f);
            setClickable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    private void showWithCrossFade() {
        if (getVisibility() == View.VISIBLE && pendingVisibility != View.GONE) {
            return;
        }
        boolean wasHidden = getVisibility() != View.VISIBLE;
        animate().cancel();
        pendingVisibility = View.VISIBLE;
        super.setVisibility(View.VISIBLE);
        setClickable(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (wasHidden) {
            setAlpha(0.0f);
        }
        animate()
                .alpha(1.0f)
                .setDuration(CROSS_FADE_DURATION_MS)
                .withEndAction(() -> {
                    if (pendingVisibility == View.VISIBLE) {
                        pendingVisibility = NO_PENDING_VISIBILITY;
                        setAlpha(1.0f);
                    }
                })
                .start();
    }

    private void hideWithCrossFade() {
        if (getVisibility() != View.VISIBLE || pendingVisibility == View.GONE) {
            return;
        }
        animate().cancel();
        pendingVisibility = View.GONE;
        setClickable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        animate()
                .alpha(0.0f)
                .setDuration(CROSS_FADE_DURATION_MS)
                .withEndAction(() -> {
                    if (pendingVisibility == View.GONE) {
                        pendingVisibility = NO_PENDING_VISIBILITY;
                        super.setVisibility(View.GONE);
                        setAlpha(1.0f);
                    }
                })
                .start();
    }
}
