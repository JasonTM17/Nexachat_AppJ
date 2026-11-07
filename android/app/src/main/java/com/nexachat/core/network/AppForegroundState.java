package com.nexachat.core.network;

import io.reactivex.rxjava3.core.Flowable;

/**
 * Process-level foreground flag — one value for the whole app, independent of
 * how many activities are alive.
 *
 * <p>Exists for the parked reconnect loop: retrying a dead backend is only worth
 * battery while the app is on screen, so {@link com.nexachat.core.network.stomp.RealtimeSession}
 * gates its periodic re-arm on this signal. An interface (not a concrete
 * lifecycle owner) so the connection loop stays JVM-testable.
 */
public interface AppForegroundState {
    /**
     * {@code true} between process onStart and onStop; hot, conflated, distinct.
     */
    Flowable<Boolean> getIsForeground();
}
