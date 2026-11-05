package com.nexachat.call.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CallAudioRoutePolicyTest {
    @Test public void speakerToggleSelectsTheExpectedRoute() {
        assertEquals(CallAudioRoutePolicy.Route.EARPIECE, CallAudioRoutePolicy.routeFor(false));
        assertEquals(CallAudioRoutePolicy.Route.SPEAKER, CallAudioRoutePolicy.routeFor(true));
    }
}
