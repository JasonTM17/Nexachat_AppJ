package com.nexachat.core.network;

import android.content.Context;
import androidx.annotation.NonNull;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.Unit;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.SharedFlow;
import kotlinx.coroutines.flow.SharingStarted;
import kotlinx.coroutines.flow.FlowKt;

/**
 * Emits once per <em>validated</em> network regain. Exists for one reason: the WS
 * reconnect loop intentionally gives up after {@link
 * com.nexachat.core.network.stomp.ReconnectPolicy#MAX_ATTEMPTS} failures (OFFLINE)
 * — a network coming back is the only signal that can re-arm it without waiting
 * for the next user action.
 *
 * <p>{@code NET_CAPABILITY_VALIDATED} filters captive-portal-style "connected but
 * not really" transitions; flap storms are harmless because the consumer
 * ({@code RealtimeSession.launchConnectionLoop}) self-dedups while a loop is alive.
 */
@Singleton
public final class NetworkMonitor {

    /**
     * One shared {@link android.net.ConnectivityManager} registration for the app
     * lifetime — {@code Lazily} starts the upstream on the first collector and never
     * stops it.
     *
     * <p>This MUST be hot: the raw {@code callbackFlow} is cold, so every collector
     * would register its own callback — and Android delivers {@code onAvailable}
     * immediately for already-connected networks at registration. A collector
     * subscribing <em>later</em> would then receive a spurious "regain" instantly.
     * {@code replay = 0} keeps that same guarantee for late subscribers.
     */
    @NonNull private final SharedFlow<Unit> regained;

    @Inject
    public NetworkMonitor(@ApplicationContext @NonNull Context context, @NonNull CoroutineScope scope) {
        this.regained = FlowKt.shareIn(
                NetworkMonitorFlow.regained(context),
                scope,
                SharingStarted.Companion.getLazily(),
                /* replay= */ 0);
    }

    @NonNull
    public SharedFlow<Unit> getRegained() {
        return regained;
    }
}
