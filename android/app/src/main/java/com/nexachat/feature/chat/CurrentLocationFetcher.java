package com.nexachat.feature.chat;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot device location for the share flow: the freshest last-known fix
 * wins immediately, otherwise a single {@code getCurrentLocation} request runs
 * with a timeout. Works on minSdk 26 — pre-30 takes {@code requestSingleUpdate}.
 * Callers must hold a location permission before invoking {@link #fetch}.
 */
public final class CurrentLocationFetcher {

    private static final long FIX_TIMEOUT_MS = 10_000L;

    public enum Status { OK, UNAVAILABLE, DISABLED }

    public interface Callback {
        void onResult(@Nullable Location location, @NonNull Status status);
    }

    private CurrentLocationFetcher() {
    }

    @SuppressLint("MissingPermission")
    public static void fetch(@NonNull Context context, @NonNull Callback callback) {
        LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            callback.onResult(null, Status.UNAVAILABLE);
            return;
        }
        List<String> providers;
        try {
            providers = manager.getProviders(true);
        } catch (RuntimeException ignored) {
            callback.onResult(null, Status.UNAVAILABLE);
            return;
        }
        if (providers == null || providers.isEmpty()) {
            callback.onResult(null, Status.DISABLED);
            return;
        }
        Location best = null;
        for (String provider : providers) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || isNewer(candidate, best))) {
                    best = candidate;
                }
            } catch (RuntimeException ignored) {
                // Provider vanished or permission revoked mid-scan; keep looking.
            }
        }
        if (best != null && isFresh(best)) {
            callback.onResult(best, Status.OK);
            return;
        }
        String provider = providers.contains(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER
                : providers.get(0);
        requestSingleFix(context, manager, provider, callback);
    }

    /** A cached fix older than this may belong to another place; ask for a new one instead. */
    private static final long MAX_LAST_KNOWN_AGE_MS = 60_000L;

    private static boolean isFresh(@NonNull Location location) {
        long nanos = location.getElapsedRealtimeNanos();
        long ageMs = nanos != 0L
                ? (android.os.SystemClock.elapsedRealtimeNanos() - nanos) / 1_000_000L
                : System.currentTimeMillis() - location.getTime();
        return ageMs >= 0L && ageMs <= MAX_LAST_KNOWN_AGE_MS;
    }

    private static boolean isNewer(@NonNull Location candidate, @NonNull Location current) {
        long candidateNanos = candidate.getElapsedRealtimeNanos();
        long currentNanos = current.getElapsedRealtimeNanos();
        if (candidateNanos != 0L && currentNanos != 0L) {
            return candidateNanos > currentNanos;
        }
        return candidate.getTime() > current.getTime();
    }

    @SuppressLint("MissingPermission")
    private static void requestSingleFix(
            @NonNull Context context,
            @NonNull LocationManager manager,
            @NonNull String provider,
            @NonNull Callback callback) {
        Handler handler = new Handler(Looper.getMainLooper());
        AtomicBoolean delivered = new AtomicBoolean(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            CancellationSignal signal = new CancellationSignal();
            Runnable timeout = () -> {
                if (delivered.compareAndSet(false, true)) {
                    signal.cancel();
                    callback.onResult(null, Status.UNAVAILABLE);
                }
            };
            try {
                manager.getCurrentLocation(
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(context),
                        location -> {
                            handler.removeCallbacks(timeout);
                            if (delivered.compareAndSet(false, true)) {
                                callback.onResult(
                                        location,
                                        location != null ? Status.OK : Status.UNAVAILABLE);
                            }
                        });
                handler.postDelayed(timeout, FIX_TIMEOUT_MS);
            } catch (RuntimeException ignored) {
                callback.onResult(null, Status.UNAVAILABLE);
            }
            return;
        }
        LocationListener[] holder = new LocationListener[1];
        Runnable timeout = () -> {
            if (delivered.compareAndSet(false, true)) {
                manager.removeUpdates(holder[0]);
                callback.onResult(null, Status.UNAVAILABLE);
            }
        };
        holder[0] = location -> {
            handler.removeCallbacks(timeout);
            manager.removeUpdates(holder[0]);
            if (delivered.compareAndSet(false, true)) {
                callback.onResult(location, location != null ? Status.OK : Status.UNAVAILABLE);
            }
        };
        try {
            manager.requestSingleUpdate(provider, holder[0], Looper.getMainLooper());
            handler.postDelayed(timeout, FIX_TIMEOUT_MS);
        } catch (RuntimeException ignored) {
            callback.onResult(null, Status.UNAVAILABLE);
        }
    }
}
