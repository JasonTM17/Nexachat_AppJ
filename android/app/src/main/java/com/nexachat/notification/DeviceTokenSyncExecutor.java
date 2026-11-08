package com.nexachat.notification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.DeviceTokenApi;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.TokenStore.PendingDeviceTokenUnregister;
import com.nexachat.core.storage.Tokens;
import com.nexachat.data.TimeAndJwt;
import com.squareup.moshi.Moshi;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import retrofit2.Response;

/** Performs one bounded, idempotent reconciliation of the local FCM token and server state. */
@Singleton
public final class DeviceTokenSyncExecutor {
    public enum Outcome { SUCCESS, RETRY, FAILURE }

    private final TokenStore tokenStore;
    private final DeviceTokenApi deviceTokenApi;
    private final FirebaseTokenProvider firebaseTokenProvider;
    private final Moshi moshi;

    @Inject
    public DeviceTokenSyncExecutor(
            @NonNull TokenStore tokenStore,
            @NonNull DeviceTokenApi deviceTokenApi,
            @NonNull FirebaseTokenProvider firebaseTokenProvider,
            @NonNull Moshi moshi) {
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.deviceTokenApi = Objects.requireNonNull(deviceTokenApi, "deviceTokenApi");
        this.firebaseTokenProvider = Objects.requireNonNull(firebaseTokenProvider, "firebaseTokenProvider");
        this.moshi = Objects.requireNonNull(moshi, "moshi");
    }

    @NonNull
    public Outcome execute(int runAttemptCount) {
        try {
            return reconcile(runAttemptCount);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            DeviceTokenDiagnostics.warnError(InterruptedException.class);
            return retryOrFail(runAttemptCount);
        } catch (Exception failedRequest) {
            DeviceTokenDiagnostics.warnError(failedRequest.getClass());
            return retryOrFail(runAttemptCount);
        }
    }

    private Outcome reconcile(int runAttemptCount) throws Exception {
        Tokens session = tokenStore.readTokens().blockingGet();
        PendingDeviceTokenUnregister pending = tokenStore.readPendingDeviceTokenUnregister().blockingGet();
        if (session == null) {
            if (pending == null) {
                DeviceTokenDiagnostics.info(DeviceTokenDiagnostics.NO_SESSION);
                return Outcome.SUCCESS;
            }
            DeviceTokenDiagnostics.warn("PENDING_UNREGISTER_NO_SESSION");
            return retryOrFail(runAttemptCount);
        }

        String userId = TimeAndJwt.userIdFromJwt(session.getAccessToken(), moshi);
        boolean firebaseConfigured = false;
        String deviceToken = tokenStore.readFcmDeviceToken().blockingGet();
        if (deviceToken == null) {
            firebaseConfigured = firebaseTokenProvider.isConfigured();
            if (firebaseConfigured) {
                deviceToken = firebaseTokenProvider.getTokenIfConfigured();
                if (deviceToken != null) {
                    tokenStore.saveFcmDeviceToken(deviceToken).blockingAwait();
                }
            }
        }

        if (pending != null && canUnregister(pending, userId, deviceToken)) {
            Response<Void> unregisterResponse = deviceTokenApi
                    .unregister(new DeviceTokenApi.UnregisterRequest(pending.getToken()), null)
                    .execute();
            if (!unregisterResponse.isSuccessful()) {
                DeviceTokenDiagnostics.warnHttp(unregisterResponse.code());
                return retryOrFail(runAttemptCount);
            }
            tokenStore.clearPendingDeviceTokenUnregister(
                    pending.getToken(), pending.getOwnerUserId()).blockingAwait();
            DeviceTokenDiagnostics.info(DeviceTokenDiagnostics.UNREGISTERED);
            pending = null;
        }

        if (deviceToken == null) {
            if (!firebaseConfigured) {
                DeviceTokenDiagnostics.info(DeviceTokenDiagnostics.FIREBASE_NOT_CONFIGURED);
                return Outcome.SUCCESS;
            }
            DeviceTokenDiagnostics.warn(DeviceTokenDiagnostics.TOKEN_NULL);
            return retryOrFail(runAttemptCount);
        }

        Response<DeviceTokenApi.RegistrationResponse> registerResponse = deviceTokenApi.register(
                new DeviceTokenApi.RegistrationRequest(deviceToken)).execute();
        if (!registerResponse.isSuccessful()) {
            DeviceTokenDiagnostics.warnHttp(registerResponse.code());
            return retryOrFail(runAttemptCount);
        }

        // A successful registration transfers a shared token to this session on the server.
        if (pending != null && deviceToken.equals(pending.getToken())) {
            tokenStore.clearPendingDeviceTokenUnregister(
                    pending.getToken(), pending.getOwnerUserId()).blockingAwait();
        }
        DeviceTokenDiagnostics.info(DeviceTokenDiagnostics.REGISTERED);
        return Outcome.SUCCESS;
    }

    private static boolean canUnregister(
            PendingDeviceTokenUnregister pending,
            @Nullable String currentUserId,
            @Nullable String currentDeviceToken) {
        if (pending.getOwnerUserId() == null || pending.getOwnerUserId().equals(currentUserId)) {
            return true;
        }
        return pending.getToken().equals(currentDeviceToken);
    }

    private static Outcome retryOrFail(int runAttemptCount) {
        return DeviceTokenRetryPolicy.shouldRetry(runAttemptCount)
                ? Outcome.RETRY
                : Outcome.FAILURE;
    }

}
