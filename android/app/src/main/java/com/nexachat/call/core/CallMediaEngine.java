package com.nexachat.call.core;

/** Peer media boundary; the call UI owns the concrete implementation. */
public interface CallMediaEngine {
    interface Listener {
        void onLocalSdp(CallSignal.Sdp sdp);
        void onLocalIce(CallSignal.IceCandidate candidate);
        void onConnected();
        void onFailed(String reason);

        /**
         * The engine's local/remote video track, or null once the track is gone.
         * Typed as Object so the core seam stays free of org.webrtc types; the
         * concrete engine emits org.webrtc.VideoTrack instances.
         */
        default void onLocalVideoTrack(Object videoTrack) {}
        default void onRemoteVideoTrack(Object videoTrack) {}

        /** The camera stopped working mid-call; the session must stop claiming it is on. */
        default void onCameraUnavailable() {}
    }

    void start(boolean isCaller, IceConfig iceConfig, Listener listener);

    default void start(boolean isCaller, CallMediaType mediaType,
                       IceConfig iceConfig, Listener listener) {
        start(isCaller, iceConfig, listener);
    }

    void onRemoteSdp(CallSignal.Sdp sdp);
    void onRemoteIce(CallSignal.IceCandidate candidate);
    void setMuted(boolean muted);
    void setSpeaker(boolean speakerOn);
    default void setCameraEnabled(boolean enabled) {}
    default void switchCamera() {}
    default void restartIce() {}

    /** Shared EGL context for video renderers, or null while no video engine is live. */
    default Object videoEglContext() { return null; }

    /** Re-emits the current local/remote tracks so a rebound UI can re-attach sinks. */
    default void replayVideoTracks() {}

    void stop();
}
