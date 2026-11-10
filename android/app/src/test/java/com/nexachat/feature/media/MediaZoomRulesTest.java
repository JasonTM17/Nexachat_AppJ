package com.nexachat.feature.media;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MediaZoomRulesTest {
    @Test public void zoomIsBoundedAndRejectsInvalidValues() {
        assertEquals(1f, MediaZoomRules.clampZoom(.5f), 0f);
        assertEquals(4f, MediaZoomRules.clampZoom(8f), 0f);
        assertEquals(1f, MediaZoomRules.clampZoom(Float.NaN), 0f);
        assertEquals(2f, MediaZoomRules.clampZoom(2f), 0f);
    }
    @Test public void panningCannotLoseTheImageOffscreen() {
        assertEquals(0f, MediaZoomRules.clampPan(300, 100, 400), 0f);
        assertEquals(200f, MediaZoomRules.clampPan(900, 800, 400), 0f);
        assertEquals(-200f, MediaZoomRules.clampPan(-900, 800, 400), 0f);
    }
    @Test public void dismissRequiresUnzoomedPredominantlyVerticalGesture() {
        assertTrue(MediaZoomRules.shouldDismiss(1, 12, 150, 96));
        assertTrue(MediaZoomRules.shouldDismiss(1, 12, -150, 96));
        assertFalse(MediaZoomRules.shouldDismiss(2, 12, 150, 96));
        assertFalse(MediaZoomRules.shouldDismiss(1, 120, 150, 96));
        assertFalse(MediaZoomRules.shouldDismiss(1, 0, 50, 96));
    }
}
