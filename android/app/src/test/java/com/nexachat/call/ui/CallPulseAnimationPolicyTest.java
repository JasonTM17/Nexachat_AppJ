package com.nexachat.call.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit test for {@link CallPulseAnimationPolicy}.
 * Verifies that the pulse ring respects reduced-motion accessibility settings
 * and call state without Android framework dependencies.
 */
public final class CallPulseAnimationPolicyTest {

    @Test
    public void testShouldAnimateWhenNormalScaleAndEnabled() {
        assertTrue(CallPulseAnimationPolicy.shouldAnimate(1.0f, true, true));
        assertTrue(CallPulseAnimationPolicy.shouldAnimate(0.5f, true, true));
        assertTrue(CallPulseAnimationPolicy.shouldAnimate(2.0f, true, true));
    }

    @Test
    public void testShouldNotAnimateWhenScaleIsZero_ReducedMotion() {
        assertFalse("Scale 0.0 must disable animation for reduced motion",
                CallPulseAnimationPolicy.shouldAnimate(0.0f, true, true));
        assertFalse("Negative scale must be treated as disabled",
                CallPulseAnimationPolicy.shouldAnimate(-0.1f, true, true));
    }

    @Test
    public void testShouldNotAnimateWhenAnimatorsDisabled() {
        assertFalse("ValueAnimator.areAnimatorsEnabled() false must suppress animation",
                CallPulseAnimationPolicy.shouldAnimate(1.0f, false, true));
    }

    @Test
    public void testShouldNotAnimateWhenCallInactive() {
        assertFalse("Inactive call must not pulse",
                CallPulseAnimationPolicy.shouldAnimate(1.0f, true, false));
        assertFalse("Inactive call with zero scale must not pulse",
                CallPulseAnimationPolicy.shouldAnimate(0.0f, true, false));
    }
}
