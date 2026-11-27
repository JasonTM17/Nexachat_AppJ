package com.nexachat.call.media;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.webrtc.PeerConnection.IceConnectionState;

/** Handles ICE timeouts on the same serialized executor as the peer connection. */
final class IceRecoveryWatchdog {
    private static final long SETUP_DISCONNECT_SECONDS = 15L;
    private static final long ESTABLISHED_RECOVERY_SECONDS = 30L;

    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> timeout;

    IceRecoveryWatchdog(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    void onState(IceConnectionState state, boolean established, Runnable onConnected, Runnable onFailure) {
        if (state == IceConnectionState.CONNECTED || state == IceConnectionState.COMPLETED) {
            cancel();
            onConnected.run();
            return;
        }
        if (established) {
            // Keep one deadline through native failure and any subsequent ICE checks.
            if (timeout == null && (state == IceConnectionState.DISCONNECTED || state == IceConnectionState.FAILED)) {
                timeout = executor.schedule(onFailure, ESTABLISHED_RECOVERY_SECONDS, TimeUnit.SECONDS);
            }
            return;
        }
        cancel();
        if (state == IceConnectionState.FAILED) {
            onFailure.run();
        } else if (state == IceConnectionState.DISCONNECTED) {
            timeout = executor.schedule(onFailure, SETUP_DISCONNECT_SECONDS, TimeUnit.SECONDS);
        }
    }

    void cancel() {
        if (timeout != null) {
            timeout.cancel(false);
            timeout = null;
        }
    }
}
