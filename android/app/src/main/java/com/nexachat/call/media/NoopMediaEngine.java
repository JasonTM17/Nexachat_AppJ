package com.nexachat.call.media;

import android.os.Handler;
import android.os.Looper;
import com.nexachat.call.core.IceConfig;
import com.nexachat.call.core.CallMediaEngine;
import com.nexachat.call.core.CallSignal;

/** Explicit signaling-only fallback used when the native WebRTC runtime is unavailable. */
public final class NoopMediaEngine implements CallMediaEngine {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Listener listener;

    @Override
    public void start(boolean isCaller, IceConfig iceConfig, Listener listener) {
        this.listener = listener;
        if (listener != null) {
            mainHandler.post(() -> {
                if (this.listener == listener) listener.onFailed("WebRTC is unavailable");
            });
        }
    }

    @Override
    public void onRemoteSdp(CallSignal.Sdp sdp) { }

    @Override
    public void onRemoteIce(CallSignal.IceCandidate candidate) { }

    @Override
    public void setMuted(boolean muted) { }

    @Override
    public void setSpeaker(boolean speakerOn) { }

    @Override
    public void restartIce() { }

    @Override
    public void stop() {
        listener = null;
    }
}
