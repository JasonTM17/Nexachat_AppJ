package com.nexachat.core.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.ErrorResponse;
import com.nexachat.core.network.dto.RefreshRequest;
import com.nexachat.core.storage.PendingRefresh;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.SessionSnapshot;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.nexachat.data.AccountDataCleaner;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import retrofit2.Response;

/** Serializes token rotation shared by startup restoration and OkHttp 401 recovery. */
@Singleton
public final class TokenRefresher {

    private final TokenStore tokenStore;
    private final SessionEvents sessionEvents;
    private final Provider<AuthApi> authApi;
    @Nullable private final AccountDataCleaner accountDataCleaner;
    private final Object lock = new Object();
    private final AtomicLong sessionEpoch = new AtomicLong();
    private final BehaviorProcessor<Boolean> admissionEvents =
            BehaviorProcessor.createDefault(false);
    private volatile boolean admissionOpen;
    private volatile boolean bootstrapComplete;
    private volatile boolean closingOrClosed;
    @Nullable private CachedFlight inFlight;
    @Nullable private volatile AuthResponse pendingSuccessResponse;
    private volatile long pendingSuccessEpoch;
    @Nullable private volatile String pendingSuccessKey;

    @Inject
    public TokenRefresher(
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull Provider<AuthApi> authApi,
            @NonNull AccountDataCleaner accountDataCleaner) {
        this.tokenStore = tokenStore;
        this.sessionEvents = sessionEvents;
        this.authApi = authApi;
        this.accountDataCleaner = accountDataCleaner;
    }

    /** Retained for deterministic tests that do not construct a Room database. */
    public TokenRefresher(
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull Provider<AuthApi> authApi) {
        this.tokenStore = tokenStore;
        this.sessionEvents = sessionEvents;
        this.authApi = authApi;
        this.accountDataCleaner = null;
    }

    /** Read-only epoch for realtime callers; never opens admission or performs IO. */
    public long sessionEpoch() {
        return sessionEpoch.get();
    }

    /**
     * True only while the supplied epoch is the active admitted session revision.
     * Never performs bootstrap or storage IO.
     */
    public boolean isSessionCurrent(long expectedEpoch) {
        return admissionOpen && sessionEpoch.get() == expectedEpoch;
    }

    /**
     * Replaying admission signal for realtime callers; never performs bootstrap or IO.
     * Starts false until a durable session is active, closes at logout/clear entry,
     * and opens only after establishment or coherent bootstrap.
     */
    @NonNull
    public Flowable<Boolean> sessionAdmission() {
        return admissionEvents.distinctUntilChanged().onBackpressureLatest();
    }

    /** Forced rotation used when restoring a session at startup. */
    @NonNull
    public Single<TokenRefreshOutcome> refresh() {
        return singleFlight(this::forcedOperation);
    }

    /** Returns a usable rotated/current token, or empty when rotation did not succeed. */
    @NonNull
    public Single<Optional<String>> refreshIfNeeded(@Nullable String failedAccessToken) {
        return refreshOutcomeIfNeeded(failedAccessToken).map(TokenRefresher::freshToken);
    }

    /** Preserves the distinction between a rejected refresh token and a retryable network failure. */
    @NonNull
    public Single<TokenRefreshOutcome> refreshOutcomeIfNeeded(@Nullable String failedAccessToken) {
        return singleFlight(epoch -> conditionalOperation(epoch, failedAccessToken));
    }

    /** Clears the stored session and drives the global logged-out navigation. */
    @NonNull
    public Completable logOut() {
        return clear();
    }

    /** Synchronous bridge for OkHttp's Authenticator thread; never call on main. */
    @NonNull
    public TokenRefreshOutcome refreshBlocking() {
        return refresh().blockingGet();
    }

    /** Synchronous bridge for OkHttp's Authenticator thread; null means no retry. */
    @Nullable
    public String refreshIfNeededBlocking(@Nullable String failedAccessToken) {
        return refreshIfNeeded(failedAccessToken).blockingGet().orElse(null);
    }

    /** Called when a new login or register persists credentials. */
    public void onSessionEstablished(long newEpoch) {
        synchronized (lock) {
            sessionEpoch.set(newEpoch);
            admissionOpen = true;
            closingOrClosed = false;
            inFlight = null;
            pendingSuccessResponse = null;
            bootstrapComplete = true;
        }
        admissionEvents.onNext(true);
    }

    /** Invalidates pending refresh writes before the session is cleared. */
    public void onLogout() {
        SessionCloseMarker marker = closeAdmissionSynchronously();
        tokenStore.reserveSessionClosing(marker.activeEpoch)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        ignored -> { },
                        ignored -> { });
    }

    /** Clears all account data and tokens, then publishes logout as one boundary. */
    @NonNull
    public Completable clear() {
        SessionCloseMarker marker = closeAdmissionSynchronously();
        Completable physicalClear = accountDataCleaner == null
                ? tokenStore.clear().andThen(
                        tokenStore.markSessionClosed(marker.closingEpoch))
                        .andThen(Completable.fromAction(sessionEvents::emitLoggedOut))
                : accountDataCleaner.clearForLogout(
                        tokenStore.clear()
                                .andThen(tokenStore.markSessionClosed(marker.closingEpoch))
                                .andThen(Completable.fromAction(sessionEvents::emitLoggedOut)));
        return Completable.fromAction(() ->
                        tokenStore.reserveSessionClosing(marker.activeEpoch).blockingGet())
                .andThen(physicalClear)
                .subscribeOn(Schedulers.io());
    }

    private SessionCloseMarker closeAdmissionSynchronously() {
        SessionCloseMarker marker;
        synchronized (lock) {
            long activeEpoch = sessionEpoch.get();
            long closingEpoch = activeEpoch + 1L;
            sessionEpoch.set(closingEpoch);
            admissionOpen = false;
            closingOrClosed = true;
            bootstrapComplete = true;
            inFlight = null;
            pendingSuccessResponse = null;
            pendingSuccessKey = null;
            marker = new SessionCloseMarker(activeEpoch, closingEpoch);
        }
        admissionEvents.onNext(false);
        return marker;
    }

    private void ensureBootstrap() {
        if (bootstrapComplete) {
            return;
        }
        synchronized (lock) {
            if (bootstrapComplete) {
                return;
            }
            if (closingOrClosed) {
                admissionOpen = false;
                bootstrapComplete = true;
                return;
            }
            SessionSnapshot snapshot = tokenStore.readSessionSnapshot().blockingGet();
            if (snapshot.getLifecycle() == SessionSnapshot.Lifecycle.ACTIVE) {
                sessionEpoch.set(snapshot.getEpoch());
                admissionOpen = true;
            } else if (snapshot.getEpoch() == 0L) {
                long epoch = tokenStore.bootstrapLegacySessionIfNeeded().blockingGet();
                if (epoch > 0L) {
                    sessionEpoch.set(epoch);
                    admissionOpen = true;
                }
            } else {
                sessionEpoch.set(snapshot.getEpoch());
                admissionOpen = false;
                closingOrClosed = snapshot.getLifecycle() != SessionSnapshot.Lifecycle.ACTIVE;
            }
            bootstrapComplete = true;
        }
        if (admissionOpen) {
            admissionEvents.onNext(true);
        }
    }

    private Single<TokenRefreshOutcome> singleFlight(
            java.util.function.LongFunction<Single<TokenRefreshOutcome>> operation) {
        ensureBootstrap();
        final long capturedEpoch;
        final long flightId = System.nanoTime();
        synchronized (lock) {
            if (!admissionOpen) {
                return Single.just(TokenRefreshOutcome.NoRefreshToken.INSTANCE);
            }
            capturedEpoch = sessionEpoch.get();
            if (inFlight != null && inFlight.epoch == capturedEpoch) {
                return fenceDelivery(inFlight.shared, capturedEpoch);
            }
            AtomicReference<CachedFlight> holder = new AtomicReference<>();
            Single<TokenRefreshOutcome> shared = Single.defer(() ->
                            operation.apply(capturedEpoch).flatMap(outcome ->
                                    fenceOutcome(outcome, capturedEpoch, flightId)))
                    .subscribeOn(Schedulers.io())
                    .doOnSuccess(outcome -> releaseOnTerminal(holder, outcome))
                    .doOnError(ignored -> clearInFlight(holder))
                    .cache();
            CachedFlight flight = new CachedFlight(capturedEpoch, flightId, shared, holder);
            holder.set(flight);
            inFlight = flight;
            return fenceDelivery(shared, capturedEpoch);
        }
    }

    private Single<TokenRefreshOutcome> forcedOperation(long capturedEpoch) {
        return tokenStore.readTokens()
                .map(Tokens::getRefreshToken)
                .flatMapSingle(token -> rotateOrRetry(token, capturedEpoch, null))
                .defaultIfEmpty(TokenRefreshOutcome.NoRefreshToken.INSTANCE);
    }

    private Single<TokenRefreshOutcome> conditionalOperation(
            long capturedEpoch, @Nullable String failedAccessToken) {
        return tokenStore.readTokens().flatMapSingle(tokens -> {
            if (!isSessionCurrent(capturedEpoch)) {
                return Single.just((TokenRefreshOutcome) TokenRefreshOutcome.NoRefreshToken.INSTANCE);
            }
            if (failedAccessToken != null
                    && !failedAccessToken.equals(tokens.getAccessToken())) {
                return Single.just(
                        (TokenRefreshOutcome) new TokenRefreshOutcome.FreshToken(
                                tokens.getAccessToken()));
            }
            return rotateOrRetry(tokens.getRefreshToken(), capturedEpoch, failedAccessToken);
        }).defaultIfEmpty(TokenRefreshOutcome.NoRefreshToken.INSTANCE);
    }

    private Single<TokenRefreshOutcome> rotateOrRetry(
            String refreshToken, long capturedEpoch, @Nullable String failedAccessToken) {
        if (!admitDispatch(capturedEpoch)) {
            return Single.just(TokenRefreshOutcome.NoRefreshToken.INSTANCE);
        }
        AuthResponse retryResponse = pendingSuccessResponse;
        if (retryResponse != null
                && pendingSuccessEpoch == capturedEpoch
                && pendingSuccessKey != null) {
            return persistSuccess(
                    capturedEpoch, pendingSuccessKey, retryResponse, failedAccessToken);
        }
        PendingRefresh record = tokenStore.readPendingRefresh().blockingGet();
        final String requestKey;
        if (record != null) {
            if (record.getSessionEpoch() != capturedEpoch
                    || !record.getPredecessorRefreshToken().equals(refreshToken)) {
                return Single.just(TokenRefreshOutcome.RecoveryRequired.INSTANCE);
            }
            requestKey = record.getRequestKey();
        } else {
            requestKey = UUID.randomUUID().toString();
        }
        final String dispatchKey = requestKey;
        if (!tokenStore.reservePendingRefresh(
                capturedEpoch, refreshToken, dispatchKey).blockingGet()) {
            return Single.just(TokenRefreshOutcome.TransientFailure.INSTANCE);
        }
        if (!admitDispatch(capturedEpoch)) {
            return Single.just(TokenRefreshOutcome.NoRefreshToken.INSTANCE);
        }
        return Single.fromCallable(() -> authApi.get()
                        .refresh(dispatchKey, new RefreshRequest(refreshToken))
                        .execute())
                .flatMap(response -> handleResponse(
                        response, capturedEpoch, dispatchKey, failedAccessToken))
                .onErrorResumeNext(error -> {
                    if (error instanceof IOException) {
                        return Single.just(TokenRefreshOutcome.TransientFailure.INSTANCE);
                    }
                    return Single.error(error);
                });
    }

    private boolean admitDispatch(long expectedEpoch) {
        return admissionOpen && sessionEpoch.get() == expectedEpoch;
    }

    private Single<TokenRefreshOutcome> handleResponse(
            Response<AuthResponse> response,
            long capturedEpoch,
            String requestKey,
            @Nullable String failedAccessToken) throws IOException {
        if (!admitDispatch(capturedEpoch)) {
            return Single.just(TokenRefreshOutcome.NoRefreshToken.INSTANCE);
        }
        AuthResponse body = response.body();
        if (response.isSuccessful() && body != null) {
            pendingSuccessResponse = body;
            pendingSuccessEpoch = capturedEpoch;
            pendingSuccessKey = requestKey;
            return persistSuccess(capturedEpoch, requestKey, body, failedAccessToken);
        }
        String rawError = response.errorBody() == null ? null : response.errorBody().string();
        String errorCode = ErrorResponse.errorCodeOf(rawError);
        int status = response.code();
        if (TokenRefreshRules.isRecoveryUnavailable(status, errorCode)) {
            return Single.just(TokenRefreshOutcome.RecoveryRequired.INSTANCE);
        }
        if (TokenRefreshRules.isDefinitiveRejection(status, errorCode)) {
            return rejectIfCurrent(capturedEpoch)
                    .andThen(Single.just(TokenRefreshOutcome.Rejected.INSTANCE));
        }
        if (TokenRefreshRules.isTransientStatus(status, errorCode)
                || status == 408
                || (status >= 400 && !TokenRefreshRules.isDefinitiveRejection(status, errorCode))) {
            return Single.just(TokenRefreshOutcome.TransientFailure.INSTANCE);
        }
        return Single.just(TokenRefreshOutcome.TransientFailure.INSTANCE);
    }

    private Single<TokenRefreshOutcome> persistSuccess(
            long capturedEpoch,
            String requestKey,
            AuthResponse body,
            @Nullable String failedAccessToken) {
        long responseDeadline = System.currentTimeMillis()
                + body.getAccessTokenExpiresIn() * 1_000L;
        Long jwtExp = jwtExpEpochMs(body.getAccessToken());
        long expiresAt = jwtExp == null
                ? responseDeadline
                : Math.min(responseDeadline, jwtExp);
        RecallWindowConfig recall = new RecallWindowConfig(
                body.getRecallWindowSeconds(), body.getGroupAdminMayRecallAfterWindow());
        return tokenStore.commitRefreshRotation(
                        capturedEpoch,
                        requestKey,
                        body.getAccessToken(),
                        expiresAt,
                        body.getRefreshToken(),
                        recall)
                .andThen(Single.fromCallable(() -> {
                    if (!admitDispatch(capturedEpoch)) {
                        return TokenRefreshOutcome.NoRefreshToken.INSTANCE;
                    }
                    pendingSuccessResponse = null;
                    pendingSuccessKey = null;
                    detachMatchingFlight(capturedEpoch);
                    return new TokenRefreshOutcome.FreshToken(body.getAccessToken());
                }))
                .onErrorResumeNext(error -> {
                    if (admitDispatch(capturedEpoch)) {
                        return Single.just(TokenRefreshOutcome.TransientFailure.INSTANCE);
                    }
                    return Single.just(TokenRefreshOutcome.NoRefreshToken.INSTANCE);
                });
    }

    private Completable rejectIfCurrent(long expectedEpoch) {
        SessionCloseMarker marker = closeAdmissionSynchronously();
        if (marker.activeEpoch != expectedEpoch) {
            return Completable.complete();
        }
        Completable finish = tokenStore.clear()
                .andThen(tokenStore.markSessionClosed(marker.closingEpoch))
                .andThen(Completable.fromAction(sessionEvents::emitLoggedOut));
        if (accountDataCleaner == null) {
            return tokenStore.reserveSessionClosing(expectedEpoch).ignoreElement().andThen(finish);
        }
        return tokenStore.reserveSessionClosing(expectedEpoch)
                .ignoreElement()
                .andThen(accountDataCleaner.clearForLogout(finish));
    }

    private void releaseOnTerminal(
            AtomicReference<CachedFlight> holder, TokenRefreshOutcome outcome) {
        if (outcome instanceof TokenRefreshOutcome.TransientFailure
                || outcome instanceof TokenRefreshOutcome.RecoveryRequired) {
            clearInFlight(holder);
            return;
        }
        if (outcome instanceof TokenRefreshOutcome.FreshToken) {
            detachFlightHolder(holder);
        }
    }

    private void detachMatchingFlight(long epoch) {
        synchronized (lock) {
            if (inFlight != null && inFlight.epoch == epoch) {
                inFlight = null;
            }
        }
    }

    private void detachFlightHolder(AtomicReference<CachedFlight> holder) {
        synchronized (lock) {
            CachedFlight flight = holder.get();
            if (flight != null && inFlight == flight) {
                inFlight = null;
            }
        }
    }

    private void clearInFlight(AtomicReference<CachedFlight> holder) {
        synchronized (lock) {
            CachedFlight flight = holder.get();
            if (flight != null && inFlight == flight) {
                inFlight = null;
            }
        }
    }

    private Single<TokenRefreshOutcome> fenceDelivery(
            Single<TokenRefreshOutcome> upstream, long capturedEpoch) {
        return upstream.flatMap(outcome -> fenceOutcome(outcome, capturedEpoch, Long.MIN_VALUE));
    }

    private Single<TokenRefreshOutcome> fenceOutcome(
            TokenRefreshOutcome outcome, long capturedEpoch, long flightId) {
        if (!isSessionCurrent(capturedEpoch)) {
            if (outcome instanceof TokenRefreshOutcome.Rejected
                    || outcome instanceof TokenRefreshOutcome.RecoveryRequired) {
                return Single.just(outcome);
            }
            return Single.just(TokenRefreshOutcome.NoRefreshToken.INSTANCE);
        }
        synchronized (lock) {
            if (inFlight != null
                    && inFlight.epoch == capturedEpoch
                    && inFlight.flightId != flightId
                    && outcome instanceof TokenRefreshOutcome.FreshToken) {
                return Single.just(TokenRefreshOutcome.NoRefreshToken.INSTANCE);
            }
        }
        return Single.just(outcome);
    }

    @Nullable
    private static Long jwtExpEpochMs(@NonNull String accessToken) {
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            String payloadJson = new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            int expIndex = payloadJson.indexOf("\"exp\":");
            if (expIndex < 0) {
                return null;
            }
            int start = expIndex + 6;
            int end = start;
            while (end < payloadJson.length()
                    && Character.isDigit(payloadJson.charAt(end))) {
                end++;
            }
            if (end == start) {
                return null;
            }
            return Long.parseLong(payloadJson.substring(start, end)) * 1_000L;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Optional<String> freshToken(TokenRefreshOutcome outcome) {
        return outcome instanceof TokenRefreshOutcome.FreshToken fresh
                ? Optional.of(fresh.getAccessToken())
                : Optional.empty();
    }

    private record SessionCloseMarker(long activeEpoch, long closingEpoch) {
    }

    private static final class CachedFlight {
        final long epoch;
        final long flightId;
        final Single<TokenRefreshOutcome> shared;
        final AtomicReference<CachedFlight> holder;

        CachedFlight(
                long epoch,
                long flightId,
                Single<TokenRefreshOutcome> shared,
                AtomicReference<CachedFlight> holder) {
            this.epoch = epoch;
            this.flightId = flightId;
            this.shared = shared;
            this.holder = holder;
        }
    }
}
