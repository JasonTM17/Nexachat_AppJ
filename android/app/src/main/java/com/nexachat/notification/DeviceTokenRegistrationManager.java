package com.nexachat.notification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.DeviceTokenApi;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.TimeAndJwt;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Persists FCM token changes and queues authenticated server synchronization. */
@Singleton
public final class DeviceTokenRegistrationManager {
    private final TokenStore tokenStore;
    private final DeviceTokenApi deviceTokenApi;
    private final DeviceTokenWorkScheduler scheduler;
    private final Moshi moshi;

    @Inject
    public DeviceTokenRegistrationManager(
            @NonNull TokenStore tokenStore,
            @NonNull DeviceTokenApi deviceTokenApi,
            @NonNull DeviceTokenWorkScheduler scheduler,
            @NonNull Moshi moshi) {
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.deviceTokenApi = Objects.requireNonNull(deviceTokenApi, "deviceTokenApi");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.moshi = Objects.requireNonNull(moshi, "moshi");
    }

    /** Saves every token callback and queues registration only for an active session. */
    @NonNull
    public Completable onNewToken(@NonNull String token) {
        if (token.isBlank()) {
            return Completable.complete();
        }
        return tokenStore.saveFcmDeviceToken(token)
                .andThen(tokenStore.readTokens()
                        .map(ignored -> true)
                        .defaultIfEmpty(false)
                        .doOnSuccess(hasSession -> {
                            if (hasSession) {
                                scheduler.enqueueSync();
                            }
                        })
                        .ignoreElement());
    }

    /** Called after login and successful or transient session restoration. */
    @NonNull
    public Completable onSessionAvailable() {
        return Completable.fromAction(scheduler::enqueueSync);
    }

    /** Persists a pending unregister before logout clears the active session. */
    @NonNull
    public Completable onLogout() {
        return tokenStore.readTokens()
                .flatMap(tokens -> tokenStore.readFcmDeviceToken()
                        .map(token -> new PendingUnregister(
                                token,
                                TimeAndJwt.userIdFromJwt(tokens.getAccessToken(), moshi),
                                tokens.getAccessToken())))
                .flatMapCompletable(pending -> tokenStore.savePendingDeviceTokenUnregister(
                                pending.token, pending.ownerUserId)
                        .andThen(Completable.fromAction(() -> enqueueUnregister(
                                pending.token, pending.ownerUserId, pending.accessToken))));
    }

    private void enqueueUnregister(
            @NonNull String token,
            @Nullable String ownerUserId,
            @NonNull String accessToken) {
        try {
            Call<Void> call = deviceTokenApi.unregister(
                    new DeviceTokenApi.UnregisterRequest(token), "Bearer " + accessToken);
            call.enqueue(new Callback<>() {
                @Override
                public void onResponse(@NonNull Call<Void> completedCall,
                        @NonNull Response<Void> response) {
                    if (!response.isSuccessful()) {
                        scheduleRetry();
                        return;
                    }
                    tokenStore.clearPendingDeviceTokenUnregister(token, ownerUserId)
                            .subscribe(() -> { }, ignored -> scheduleRetry());
                }

                @Override
                public void onFailure(@NonNull Call<Void> failedCall, @NonNull Throwable error) {
                    scheduleRetry();
                }
            });
        } catch (RuntimeException unavailable) {
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        try {
            scheduler.enqueueSync();
        } catch (RuntimeException ignored) {
            // The encrypted pending request remains for the next session-triggered sync.
        }
    }

    private static final class PendingUnregister {
        private final String token;
        @Nullable private final String ownerUserId;
        private final String accessToken;

        private PendingUnregister(String token, @Nullable String ownerUserId, String accessToken) {
            this.token = token;
            this.ownerUserId = ownerUserId;
            this.accessToken = accessToken;
        }
    }
}
