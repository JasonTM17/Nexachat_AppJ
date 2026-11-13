package com.nexachat.core.ui.components;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AvatarPresenceDotRulesTest {

  private static final float DENSITY = 2.75f;
    private static final int[] AVATAR_DP_SIZES = {24, 32, 40, 48, 56, 72, 96};

    @Test
    public void shouldShowDot_onlyWhenOnlineTrue() {
        assertTrue(AvatarPresenceDotRules.shouldShowDot(Boolean.TRUE));
        assertFalse(AvatarPresenceDotRules.shouldShowDot(Boolean.FALSE));
        assertFalse(AvatarPresenceDotRules.shouldShowDot(null));
    }

    @Test
    public void dotDiameterDp_clampsBetweenTenAndSixteenAcrossStandardAvatarSizes() {
        for (int avatarDp : AVATAR_DP_SIZES) {
            int dotDp = AvatarPresenceDotRules.dotDiameterDp(avatarDp);
            assertTrue(avatarDp + "dp dot " + dotDp, dotDp >= AvatarPresenceDotRules.MIN_DOT_DP);
            assertTrue(avatarDp + "dp dot " + dotDp, dotDp <= AvatarPresenceDotRules.MAX_DOT_DP);
        }
        assertEquals(10, AvatarPresenceDotRules.dotDiameterDp(24));
        assertEquals(16, AvatarPresenceDotRules.dotDiameterDp(56));
        assertEquals(16, AvatarPresenceDotRules.dotDiameterDp(96));
    }

    @Test
    public void dotUsesPointTwoEightScaleBeforeClamping() {
        assertEquals(11, AvatarPresenceDotRules.dotDiameterDp(40));
        assertEquals(13, AvatarPresenceDotRules.dotDiameterDp(48));
    }

    @Test
    public void cornerInsetPullsDotTowardCircleEdge() {
        int dotDp = AvatarPresenceDotRules.dotDiameterDp(56);
        int insetDp = AvatarPresenceDotRules.dotCornerInsetDp(dotDp);
        assertTrue(insetDp > 0);
        assertTrue(insetDp < dotDp);
    }

    @Test
    public void dotDiameterPx_matchesDpAtDensity() {
        int avatarPx = Math.round(56 * DENSITY);
        int dotPx = AvatarPresenceDotRules.dotDiameterPx(avatarPx, DENSITY);
        int expectedPx = Math.round(AvatarPresenceDotRules.dotDiameterDp(56) * DENSITY);
        assertEquals(expectedPx, dotPx);
    }
}
