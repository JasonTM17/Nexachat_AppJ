package com.nexachat.feature.inbox;

/** Whether the Active Now rail may play its one-shot entrance motion. */
public final class ActiveNowEntranceAnimationPolicy {

    private ActiveNowEntranceAnimationPolicy() {}

    public static boolean shouldPlayEntrance(
            float animatorDurationScale,
            boolean animatorsEnabled,
            boolean stripAlreadyAnimated) {
        if (stripAlreadyAnimated) {
            return false;
        }
        if (animatorDurationScale <= 0f || !animatorsEnabled) {
            return false;
        }
        return true;
    }
}
