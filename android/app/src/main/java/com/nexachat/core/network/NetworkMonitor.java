package com.nexachat.core.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import androidx.annotation.NonNull;
import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Shared validated-connectivity state used to re-arm realtime reconnects. */
@Singleton
public final class NetworkMonitor {

    private final Flowable<Boolean> isOnline;

    @Inject
    public NetworkMonitor(@ApplicationContext @NonNull Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();
        isOnline = Flowable.<Boolean>create(emitter -> {
            ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    emitter.onNext(true);
                }

                @Override
                public void onLost(@NonNull Network network) {
                    emitter.onNext(hasValidatedNetwork(manager));
                }
            };
            emitter.onNext(hasValidatedNetwork(manager));
            manager.registerNetworkCallback(request, callback);
            emitter.setCancellable(() -> manager.unregisterNetworkCallback(callback));
        }, BackpressureStrategy.LATEST)
                .distinctUntilChanged()
                .replay(1)
                .refCount();
    }

    @NonNull
    public Flowable<Boolean> getIsOnline() {
        return isOnline;
    }

    private static boolean hasValidatedNetwork(ConnectivityManager manager) {
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
