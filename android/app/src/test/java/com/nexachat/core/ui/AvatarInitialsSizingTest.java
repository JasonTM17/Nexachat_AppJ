package com.nexachat.core.ui;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class AvatarInitialsSizingTest {

    private static final float DENSITY = 2.5f;

    @Test
    public void twoInitialsAtSmallAvatarStayWithinDiameterCapAtLargeFontScale() {
        int diameterPx = Math.round(32f * DENSITY);
        float textPx = AvatarInitialsSizing.textSizePx(diameterPx, "Nguyen An", DENSITY);
        assertTrue("Two-letter cap should fit inside a 32dp avatar",
                textPx <= diameterPx * 0.36f + 0.5f);
    }

    @Test
    public void singleInitialKeepsReadableSizeOnLargeAvatar() {
        int diameterPx = Math.round(72f * DENSITY);
        float textPx = AvatarInitialsSizing.textSizePx(diameterPx, "Binh", DENSITY);
        assertTrue(textPx >= 20f * DENSITY);
        assertTrue(textPx <= diameterPx * 0.44f + 0.5f);
    }

    @Test
    public void sizingIgnoresFontScaleBecausePixelsAreUsed() {
        int diameterPx = Math.round(36f * DENSITY);
        float one = AvatarInitialsSizing.textSizePx(diameterPx, "AB", DENSITY);
        float two = AvatarInitialsSizing.textSizePx(diameterPx, "Nguyen Van", DENSITY);
        assertTrue(one > 0f);
        assertEqualsWithin(one, two, 0.01f);
    }

    private static void assertEqualsWithin(float expected, float actual, float epsilon) {
        assertTrue(Math.abs(expected - actual) <= epsilon);
    }
}
