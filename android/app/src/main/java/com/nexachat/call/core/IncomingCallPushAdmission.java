package com.nexachat.call.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.TimeAndJwt;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.inject.Inject;

/** Re-admits a persisted call notification through the current account and ring checks. */
public final class IncomingCallPushAdmission {
    private final CallManager callManager;
    private final TokenRefresher tokenRefresher;
    private final TokenStore tokenStore;
    private final Moshi moshi;
    private final Scheduler ioScheduler;
    private final LongSupplier clock;

    @Inject
    public IncomingCallPushAdmission(
            @NonNull CallManager callManager,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull TokenStore tokenStore,
            @NonNull Moshi moshi) {
        this(callManager, tokenRefresher, tokenStore, moshi,
                Schedulers.io(), System::currentTimeMillis);
    }

    @VisibleForTesting
    public IncomingCallPushAdmission(
            @NonNull CallManager callManager,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull TokenStore tokenStore,
            @NonNull Moshi moshi,
            @NonNull Scheduler ioScheduler,
            @NonNull LongSupplier clock) {
        this.callManager = callManager;
        this.tokenRefresher = tokenRefresher;
        this.tokenStore = tokenStore;
        this.moshi = moshi;
        this.ioScheduler = ioScheduler;
        this.clock = clock;
    }

    @NonNull
    public Maybe<CallSession> admit(@Nullable IncomingCallPushData data) {
        if (data == null || !data.isValidAt(clock.getAsLong())) return Maybe.empty();
        return currentAccessToken(data.getSessionEpoch())
                .subscribeOn(ioScheduler)
                .flatMap(accessToken -> {
                    String currentUserId = TimeAndJwt.userIdFromJwt(accessToken, moshi);
                    if (!data.getRecipientId().equals(currentUserId)
                            || !tokenRefresher.isSessionCurrent(data.getSessionEpoch())) {
                        return Maybe.empty();
                    }
                    return callManager.admitIncomingPush(
                            data.getCallId(),
                            data.getConversationId(),
                            data.getCallerId(),
                            data.getCallerName(),
                            data.getServerTs(),
                            data.getRecipientId(),
                            data.getSessionEpoch(),
                            data.getRingExpiresAtMs(),
                            () -> tokenRefresher.isSessionCurrent(data.getSessionEpoch()));
                });
    }

    /** Runs an action only while the exact call admission and account epoch remain current. */
    public boolean runIfCurrent(
            @Nullable IncomingCallPushData data,
            @NonNull Runnable action) {
        if (data == null || !data.isValidAt(clock.getAsLong())) return false;
        AtomicBoolean ran = new AtomicBoolean();
        boolean sessionCurrent = tokenRefresher.runIfSessionCurrent(
                data.getSessionEpoch(), () -> {
                    if (data.isValidAt(clock.getAsLong())
                            && data.matches(callManager.currentSessionSnapshot())
                            && callManager.isIncomingPushAdmissionCurrent(
                                    data.getCallId(),
                                    data.getRecipientId(),
                                    data.getSessionEpoch(),
                                    data.getRingExpiresAtMs())) {
                        action.run();
                        ran.set(true);
                    }
                });
        return sessionCurrent && ran.get();
    }

    /** Re-admits a cold call push, then runs its action without creating an activity. */
    @NonNull
    public Maybe<Boolean> admitAndRunIfCurrent(
            @Nullable IncomingCallPushData data,
            @NonNull Runnable action) {
        if (data == null || !data.isValidAt(clock.getAsLong())) return Maybe.just(false);
        if (runIfCurrent(data, action)) return Maybe.just(true);
        return admit(data)
                .map(ignored -> runIfCurrent(data, action))
                .switchIfEmpty(Maybe.just(false));
    }

    private Maybe<String> currentAccessToken(long expectedEpoch) {
        if (tokenRefresher.isSessionCurrent(expectedEpoch)) {
            return tokenStore.readTokens()
                    .filter(ignored -> tokenRefresher.isSessionCurrent(expectedEpoch))
                    .map(tokens -> tokens.getAccessToken());
        }
        return tokenRefresher.refreshIfNeeded(null).flatMapMaybe(accessToken -> {
            Optional<String> restored = accessToken;
            if (restored.isEmpty() || !tokenRefresher.isSessionCurrent(expectedEpoch)) {
                return Maybe.empty();
            }
            return Maybe.just(restored.get());
        });
    }
}
