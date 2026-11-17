package com.nexachat.call.ui;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks whether the remote video track is still delivering frames. A peer that turns its camera
 * off stops sending frames, so the call screen falls back to the avatar instead of keeping the
 * last picture. Frames arrive on the decoder thread; {@link #isFlowing} is read on the UI thread.
 */
final class RemoteVideoFlow {

    /** No frame for this long counts as a stopped camera. */
    static final long STALL_MS = 1500L;

    private static final long NO_FRAME = Long.MIN_VALUE;

    private final AtomicLong lastFrameMs = new AtomicLong(NO_FRAME);

    /** Records a frame; returns true when this frame ends a stall (or is the first one). */
    boolean onFrame(long nowMs) {
        long previous = lastFrameMs.getAndSet(nowMs);
        return previous == NO_FRAME || nowMs - previous >= STALL_MS;
    }

    boolean isFlowing(long nowMs) {
        long last = lastFrameMs.get();
        return last != NO_FRAME && nowMs - last < STALL_MS;
    }

    void reset() {
        lastFrameMs.set(NO_FRAME);
    }
}
