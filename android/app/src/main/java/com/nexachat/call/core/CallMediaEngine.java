package com.nexachat.call.core;

/** Audio-only peer media boundary; the call UI owns the concrete implementation. */
public interface CallMediaEngine {
    interface Listener {
        void onLocalSdp(CallSignal.Sdp sdp);
        void onLocalIce(CallSignal.IceCandidate candidate);
        void onConnected();
        void onFailed(String reason);
    }

    void start(boolean isCaller, IceConfig iceConfig, Listener listener);
    void onRemoteSdp(CallSignal.Sdp sdp);
    void onRemoteIce(CallSignal.IceCandidate candidate);
    void setMuted(boolean muted);
    void setSpeaker(boolean speakerOn);
    void stop();
}
