package com.nexachat.feature.chat.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WaveformViewGeometryTest {

    @Test
    public void emptyBarsDrawOneMinimumHeightPlaceholder() {
        WaveformView.BarGeometry[] bars = WaveformView.calculateBarGeometry(
                120f, 48f, new byte[0], 2f, 4f, false, 0f);

        assertEquals(1, bars.length);
        assertEquals(4f, bars[0].bottom - bars[0].top, 0f);
    }

    @Test
    public void oneBarIsCenteredAndUsesProgressState() {
        WaveformView.BarGeometry[] bars = WaveformView.calculateBarGeometry(
                120f, 48f, new byte[]{(byte) 255}, 2f, 3f, false, 1f);

        assertEquals(1, bars.length);
        assertEquals(0f, bars[0].left, 0f);
        assertEquals(120f, bars[0].right, 0f);
        assertTrue(bars[0].played);
    }

    @Test
    public void rtlReversesPlayedPortion() {
        WaveformView.BarGeometry[] bars = WaveformView.calculateBarGeometry(
                100f, 20f, new byte[]{1, 1, 1, 1}, 0f, 2f, true, 0.5f);

        assertTrue(!bars[0].played);
        assertTrue(!bars[1].played);
        assertTrue(bars[2].played);
        assertTrue(bars[3].played);
    }
}
