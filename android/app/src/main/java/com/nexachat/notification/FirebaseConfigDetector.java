package com.nexachat.notification;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.firebase.FirebaseApp;
import java.util.List;

/**
 * Detects whether the default Firebase app was initialized from google-services.json.
 * A build without that file has no default app and must not touch FirebaseMessaging.
 */
public final class FirebaseConfigDetector {

    private FirebaseConfigDetector() { }

    /** True only when a default app with a non-blank application id is initialized. */
    public static boolean isDefaultAppConfigured(@NonNull Context context) {
        try {
            return isDefaultAppConfigured(FirebaseApp.getApps(context.getApplicationContext()));
        } catch (RuntimeException unavailable) {
            // The Firebase runtime is absent or not initialized; treat it as unconfigured.
            return false;
        }
    }

    @VisibleForTesting
    public static boolean isDefaultAppConfigured(@NonNull List<FirebaseApp> apps) {
        for (FirebaseApp app : apps) {
            if (isDefaultApp(app)) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    public static boolean isDefaultApp(@Nullable FirebaseApp app) {
        if (app == null || !FirebaseApp.DEFAULT_APP_NAME.equals(app.getName())) {
            return false;
        }
        try {
            String applicationId = app.getOptions().getApplicationId();
            return applicationId != null && !applicationId.isBlank();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
