package com.nexachat.call.ui;

/**
 * Pure-logic policy deciding whether the call-screen soft pulse ring animation should run.
 * Respects system reduced-motion settings (ANIMATOR_DURATION_SCALE == 0 or animators disabled).
 */
public final class CallPulseAnimationPolicy {

    private CallPulseAnimationPolicy() { }

    /**
     * Determines whether the soft pulse ring animation is allowed to run.
     *
     * @param animatorDurationScale The system ANIMATOR_DURATION_SCALE (0.0 means animations disabled).
     * @param animatorsEnabled      Result from ValueAnimator.areAnimatorsEnabled().
     * @param isCallActiveOrRinging  Whether the call session is in an active or ringing state.
     * @return true if animation should run, false if it should be stopped or disabled (reduced motion).
     */
    public static boolean shouldAnimate(
            float animatorDurationScale,
            boolean animatorsEnabled,
            boolean isCallActiveOrRinging) {
        if (!isCallActiveOrRinging) {
            return false;
        }
        if (animatorDurationScale <= 0f) {
            return false;
        }
        return animatorsEnabled;
    }
}
