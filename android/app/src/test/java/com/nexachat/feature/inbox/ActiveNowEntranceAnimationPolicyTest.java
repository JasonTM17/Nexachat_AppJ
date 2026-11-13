package com.nexachat.feature.inbox;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ActiveNowEntranceAnimationPolicyTest {

    @Test
    public void shouldPlayEntrance_onlyOnceWhenMotionAllowed() {
        assertTrue(ActiveNowEntranceAnimationPolicy.shouldPlayEntrance(1f, true, false));
        assertFalse(ActiveNowEntranceAnimationPolicy.shouldPlayEntrance(1f, true, true));
        assertFalse(ActiveNowEntranceAnimationPolicy.shouldPlayEntrance(0f, true, false));
        assertFalse(ActiveNowEntranceAnimationPolicy.shouldPlayEntrance(1f, false, false));
    }
}
