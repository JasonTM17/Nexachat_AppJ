package com.nexachat.call.ui;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;

import org.webrtc.VideoFrame;

/**
 * Cheap check for a remote video frame that is entirely black. Some peers keep sending black frames
 * after turning their camera off; the call screen treats those like a stopped camera. Only a sparse
 * grid of luma samples is read, and the caller samples a frame about once a second.
 */
final class BlackFrameDetector {

    /** Luma at or below this reads as black (limited-range black is 16). */
    static final int BLACK_MAX_LUMA = 24;

    private static final int GRID = 8;

    private BlackFrameDetector() { }

    /** Returns true only when every sampled pixel is black; any failure counts as "not black". */
    static boolean isBlack(@NonNull VideoFrame frame) {
        VideoFrame.I420Buffer i420 = null;
        try {
            i420 = frame.getBuffer().toI420();
            if (i420 == null) return false;
            return isBlack(i420.getDataY(), i420.getStrideY(), i420.getWidth(), i420.getHeight());
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (i420 != null) i420.release();
        }
    }

    static boolean isBlack(@NonNull ByteBuffer luma, int stride, int width, int height) {
        if (width < GRID || height < GRID || stride < width) return false;
        for (int row = 0; row < GRID; row++) {
            int y = (int) (((row + 0.5) * height) / GRID);
            for (int column = 0; column < GRID; column++) {
                int x = (int) (((column + 0.5) * width) / GRID);
                int index = y * stride + x;
                if (index >= luma.limit()) return false;
                if ((luma.get(index) & 0xFF) > BLACK_MAX_LUMA) return false;
            }
        }
        return true;
    }
}
