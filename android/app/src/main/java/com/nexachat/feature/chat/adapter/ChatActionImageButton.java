package com.nexachat.feature.chat.adapter;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;
import com.nexachat.R;

/** Image button that fades between visible and hidden composer actions. */
public final class ChatActionImageButton extends AppCompatImageButton {

    private static final long CROSS_FADE_DURATION_MS = 150L;
    private static final PathInterpolator CROSS_FADE_INTERPOLATOR = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final int NO_PENDING_VISIBILITY = -1;
    private static final int ACCESSIBILITY_ACTION_CANCEL_VOICE_RECORDING = 0x7F010001;

    private int pendingVisibility = NO_PENDING_VISIBILITY;
    private boolean accessibleVoiceRecording;
    @Nullable private Runnable accessibilityCancelVoiceRecording;

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

    /**
     * When recording was started through an accessibility click, expose an explicit cancel action.
     */
    public void setAccessibleVoiceRecording(
            boolean recording,
            @Nullable Runnable onAccessibilityCancel) {
        accessibleVoiceRecording = recording;
        accessibilityCancelVoiceRecording = recording ? onAccessibilityCancel : null;
        if (recording) {
            setClickable(true);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (accessibleVoiceRecording && accessibilityCancelVoiceRecording != null) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    ACCESSIBILITY_ACTION_CANCEL_VOICE_RECORDING,
                    getContext().getString(R.string.action_cancel)));
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
        if (action == ACCESSIBILITY_ACTION_CANCEL_VOICE_RECORDING
                && accessibleVoiceRecording
                && accessibilityCancelVoiceRecording != null) {
            accessibilityCancelVoiceRecording.run();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
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
                .setInterpolator(CROSS_FADE_INTERPOLATOR)
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
                .setInterpolator(CROSS_FADE_INTERPOLATOR)
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

    @Override
    protected void onDetachedFromWindow() {
        animate().cancel();
        pendingVisibility = NO_PENDING_VISIBILITY;
        accessibleVoiceRecording = false;
        accessibilityCancelVoiceRecording = null;
        super.onDetachedFromWindow();
    }
}
