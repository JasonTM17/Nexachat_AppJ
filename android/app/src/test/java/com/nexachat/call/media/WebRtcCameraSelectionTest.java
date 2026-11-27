package com.nexachat.call.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.webrtc.CameraEnumerator;

public final class WebRtcCameraSelectionTest {
    @Test
    public void switchesAcrossFacingGroupsWithMultipleRearLenses() {
        CameraEnumerator cameras = mock(CameraEnumerator.class);
        when(cameras.getDeviceNames()).thenReturn(new String[] {"rear-wide", "rear-tele", "selfie"});
        when(cameras.isBackFacing("rear-wide")).thenReturn(true);
        when(cameras.isBackFacing("rear-tele")).thenReturn(true);
        when(cameras.isFrontFacing("selfie")).thenReturn(true);

        assertEquals("rear-wide", WebRtcMediaEngine.oppositeCameraName(cameras, true));
        assertEquals("selfie", WebRtcMediaEngine.oppositeCameraName(cameras, false));
    }

    @Test
    public void doesNotSelectTheSameSideWhenTheOppositeCameraIsUnavailable() {
        CameraEnumerator cameras = mock(CameraEnumerator.class);
        when(cameras.getDeviceNames()).thenReturn(new String[] {"rear-wide", "rear-tele", "external"});
        when(cameras.isBackFacing("rear-wide")).thenReturn(true);
        when(cameras.isBackFacing("rear-tele")).thenReturn(true);

        assertNull(WebRtcMediaEngine.oppositeCameraName(cameras, false));
        assertNull(WebRtcMediaEngine.oppositeCameraName(null, true));
    }
}
