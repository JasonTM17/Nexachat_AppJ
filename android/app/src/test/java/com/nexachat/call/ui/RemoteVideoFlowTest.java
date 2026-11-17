package com.nexachat.call.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RemoteVideoFlowTest {

    @Test
    public void notFlowingBeforeTheFirstFrame() {
        RemoteVideoFlow flow = new RemoteVideoFlow();
        assertFalse(flow.isFlowing(10_000L));
    }

    @Test
    public void firstFrameStartsTheFlow() {
        RemoteVideoFlow flow = new RemoteVideoFlow();
        assertTrue(flow.onFrame(1_000L));
        assertTrue(flow.isFlowing(1_000L + RemoteVideoFlow.STALL_MS - 1));
    }

    @Test
    public void flowStopsWhenFramesStall() {
        RemoteVideoFlow flow = new RemoteVideoFlow();
        flow.onFrame(1_000L);
        assertFalse(flow.isFlowing(1_000L + RemoteVideoFlow.STALL_MS));
    }

    @Test
    public void steadyFramesDoNotReportATransition() {
        RemoteVideoFlow flow = new RemoteVideoFlow();
        flow.onFrame(1_000L);
        assertFalse(flow.onFrame(1_033L));
        assertTrue(flow.isFlowing(1_040L));
    }

    @Test
    public void resumedFramesReportATransition() {
        RemoteVideoFlow flow = new RemoteVideoFlow();
        flow.onFrame(1_000L);
        assertTrue(flow.onFrame(1_000L + RemoteVideoFlow.STALL_MS + 200L));
        assertTrue(flow.isFlowing(1_000L + RemoteVideoFlow.STALL_MS + 200L));
    }

    @Test
    public void resetClearsTheFlow() {
        RemoteVideoFlow flow = new RemoteVideoFlow();
        flow.onFrame(1_000L);
        flow.reset();
        assertFalse(flow.isFlowing(1_001L));
        assertTrue(flow.onFrame(2_000L));
    }
}
