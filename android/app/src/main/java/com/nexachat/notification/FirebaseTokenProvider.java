package com.nexachat.notification;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import dagger.hilt.android.qualifiers.ApplicationContext;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Fetches the current FCM token only when the default Firebase app is configured. */
@Singleton
public final class FirebaseTokenProvider {
    private static final long TOKEN_FETCH_TIMEOUT_SECONDS = 20L;

    private final Context appContext;

    @Inject
    public FirebaseTokenProvider(@ApplicationContext @NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Returns null when this build has no Firebase options; retrieval failures propagate for retry. */
    @Nullable
    public String getTokenIfConfigured() throws Exception {
        FirebaseApp configuredApp = null;
        for (FirebaseApp app : FirebaseApp.getApps(appContext)) {
            if (FirebaseApp.DEFAULT_APP_NAME.equals(app.getName())
                    && app.getOptions().getApplicationId() != null
                    && !app.getOptions().getApplicationId().isBlank()) {
                configuredApp = app;
                break;
            }
        }
        if (configuredApp == null) {
            return null;
        }

        String token = Tasks.await(
                FirebaseMessaging.getInstance().getToken(),
                TOKEN_FETCH_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
        return token == null || token.isBlank() ? null : token;
    }
}
