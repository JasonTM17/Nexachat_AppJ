package com.nexachat.notification;

import androidx.annotation.Nullable;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.concurrent.TimeUnit;

/** Fetches the FCM registration token through the Firebase runtime. */
final class FirebaseTokenSource implements FirebaseTokenProvider.TokenSource {
    private static final long TOKEN_FETCH_TIMEOUT_SECONDS = 20L;

    @Nullable
    @Override
    public String fetchToken() throws Exception {
        return Tasks.await(
                FirebaseMessaging.getInstance().getToken(),
                TOKEN_FETCH_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
    }
}
