package com.nexachat.notification;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Fetches the current FCM token only when the default Firebase app is configured.
 * Failures propagate so the caller can retry through the device-token WorkManager job.
 */
@Singleton
public final class FirebaseTokenProvider {

    /** Seam so JVM tests can exercise the configured/absent paths without Play Services. */
    @FunctionalInterface
    public interface FirebaseConfigProbe {
        boolean isDefaultAppConfigured();
    }

    /** Seam so JVM tests can supply a token or a failure without the Firebase runtime. */
    @FunctionalInterface
    public interface TokenSource {
        @Nullable
        String fetchToken() throws Exception;
    }

    private final FirebaseConfigProbe configProbe;
    private final TokenSource tokenSource;

    @Inject
    public FirebaseTokenProvider(@ApplicationContext @NonNull Context context) {
        this(() -> FirebaseConfigDetector.isDefaultAppConfigured(context), new FirebaseTokenSource());
    }

    @VisibleForTesting
    public FirebaseTokenProvider(
            @NonNull FirebaseConfigProbe configProbe,
            @NonNull TokenSource tokenSource) {
        this.configProbe = configProbe;
        this.tokenSource = tokenSource;
    }

    /** True only when a default Firebase app is initialized, so a token fetch is safe to attempt. */
    public boolean isConfigured() {
        return configProbe.isDefaultAppConfigured();
    }

    /** Returns null when this build has no Firebase options; retrieval failures propagate for retry. */
    @Nullable
    public String getTokenIfConfigured() throws Exception {
        if (!isConfigured()) {
            return null;
        }
        String token = tokenSource.fetchToken();
        return token == null || token.isBlank() ? null : token;
    }
}
