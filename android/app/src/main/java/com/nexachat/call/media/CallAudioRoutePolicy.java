package com.nexachat.call.media;

/** Pure policy for choosing the call output route. */
public final class CallAudioRoutePolicy {
    public enum Route { EARPIECE, SPEAKER }

    private CallAudioRoutePolicy() { }

    public static Route routeFor(boolean speakerOn) {
        return speakerOn ? Route.SPEAKER : Route.EARPIECE;
    }
}
