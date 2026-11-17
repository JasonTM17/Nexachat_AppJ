package com.nexachat.call.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.Test;

public final class BlackFrameDetectorTest {

    private static ByteBuffer plane(int stride, int height, int value) {
        byte[] data = new byte[stride * height];
        Arrays.fill(data, (byte) value);
        return ByteBuffer.wrap(data);
    }

    @Test
    public void limitedRangeBlackIsBlack() {
        assertTrue(BlackFrameDetector.isBlack(plane(640, 480, 16), 640, 640, 480));
    }

    @Test
    public void midGrayIsNotBlack() {
        assertFalse(BlackFrameDetector.isBlack(plane(640, 480, 128), 640, 640, 480));
    }

    @Test
    public void oneBrightSampleMakesTheFrameVideo() {
        ByteBuffer luma = plane(640, 480, 16);
        int y = (int) ((3 + 0.5) * 480 / 8);
        int x = (int) ((4 + 0.5) * 640 / 8);
        luma.put(y * 640 + x, (byte) 200);
        assertFalse(BlackFrameDetector.isBlack(luma, 640, 640, 480));
    }

    @Test
    public void paddedStrideIsRespected() {
        ByteBuffer luma = plane(704, 480, 16);
        assertTrue(BlackFrameDetector.isBlack(luma, 704, 640, 480));
    }

    @Test
    public void tinyOrInvalidFramesAreNotBlack() {
        assertFalse(BlackFrameDetector.isBlack(plane(4, 4, 16), 4, 4, 4));
        assertFalse(BlackFrameDetector.isBlack(plane(100, 100, 16), 50, 100, 100));
    }
}
