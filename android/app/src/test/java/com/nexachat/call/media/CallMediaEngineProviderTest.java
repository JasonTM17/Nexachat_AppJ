package com.nexachat.call.media;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.nexachat.call.core.CallMediaEngine;
import com.nexachat.call.core.CallSignal;
import com.nexachat.call.core.IceConfig;
import org.junit.Test;

public class CallMediaEngineProviderTest {
    @Test public void injectedFactorySuppliesEngineWithoutLoadingNativeWebRtc() {
        CallMediaEngine expected = new FakeMediaEngine();
        CallMediaEngine actual = CallMediaEngineProvider.create(null, applicationContext -> {
            assertNull(applicationContext);
            return expected;
        });
        assertSame(expected, actual);
    }

    private static final class FakeMediaEngine implements CallMediaEngine {
        @Override public void start(boolean isCaller, IceConfig iceConfig, Listener listener) { }
        @Override public void onRemoteSdp(CallSignal.Sdp sdp) { }
        @Override public void onRemoteIce(CallSignal.IceCandidate candidate) { }
        @Override public void setMuted(boolean muted) { }
        @Override public void setSpeaker(boolean speakerOn) { }
        @Override public void stop() { }
    }
}
