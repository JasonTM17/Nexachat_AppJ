package com.nexachat.core.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.ErrorResponse;
import com.nexachat.core.network.dto.RefreshRequest;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.nexachat.data.AccountDataCleaner;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
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
    private final AtomicLong generation = new AtomicLong();
    @Nullable private Single<TokenRefreshOutcome> inFlight;

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

    /** Forced rotation used when restoring a session at startup. */
    @NonNull
    public Single<TokenRefreshOutcome> refresh() {
        return singleFlight(() -> {
            long startedGeneration = generation.get();
            return tokenStore.readTokens()
                    .map(Tokens::getRefreshToken)
                    .flatMapSingle(token -> performRefresh(token, startedGeneration))
                    .defaultIfEmpty(TokenRefreshOutcome.NoRefreshToken.INSTANCE);
        });
    }

    /** Returns a usable rotated/current token, or empty when rotation did not succeed. */
    @NonNull
    public Single<Optional<String>> refreshIfNeeded(@Nullable String failedAccessToken) {
        return refreshOutcomeIfNeeded(failedAccessToken).map(TokenRefresher::freshToken);
    }

    /** Preserves the distinction between a rejected refresh token and a retryable network failure. */
    @NonNull
    public Single<TokenRefreshOutcome> refreshOutcomeIfNeeded(@Nullable String failedAccessToken) {
        return singleFlight(() -> {
            long startedGeneration = generation.get();
            return tokenStore.readTokens().flatMapSingle(tokens -> {
                    if (generation.get() != startedGeneration) {
                        return Single.just((TokenRefreshOutcome) TokenRefreshOutcome.NoRefreshToken.INSTANCE);
                    }
                    if (failedAccessToken != null
                            && !failedAccessToken.equals(tokens.getAccessToken())) {
                        return Single.just((TokenRefreshOutcome)
                                new TokenRefreshOutcome.FreshToken(tokens.getAccessToken()));
                    }
                    return performRefresh(tokens.getRefreshToken(), startedGeneration);
                })
                .defaultIfEmpty(TokenRefreshOutcome.NoRefreshToken.INSTANCE);
        });
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

    /** Invalidates pending refresh writes before the session is cleared. */
    public void onLogout() {
        synchronized (lock) {
            generation.incrementAndGet();
            inFlight = null;
        }
    }

    /** Clears all account data and tokens, then publishes logout as one boundary. */
    @NonNull
    public Completable clear() {
        return Completable.fromAction(() -> {
            synchronized (lock) {
                generation.incrementAndGet();
                inFlight = null;
            }
        }).andThen(accountDataCleaner == null
                ? tokenStore.clear().andThen(Completable.fromAction(sessionEvents::emitLoggedOut))
                : accountDataCleaner.clearForLogout(tokenStore.clear()
                        .andThen(Completable.fromAction(sessionEvents::emitLoggedOut))))
                .subscribeOn(Schedulers.io());
    }

    private Single<TokenRefreshOutcome> singleFlight(
            java.util.function.Supplier<Single<TokenRefreshOutcome>> operation) {
        synchronized (lock) {
            if (inFlight != null) {
                return inFlight;
            }
            AtomicReference<Single<TokenRefreshOutcome>> holder = new AtomicReference<>();
            Single<TokenRefreshOutcome> shared = Single.defer(operation::get)
                    .subscribeOn(Schedulers.io())
                    .doOnSuccess(outcome -> {
                        if (outcome instanceof TokenRefreshOutcome.TransientFailure) {
                            clearInFlight(holder);
                        }
                    })
                    .doOnError(ignored -> clearInFlight(holder))
                    .doFinally(() -> Schedulers.io().scheduleDirect(() -> clearInFlight(holder)))
                    .cache();
            holder.set(shared);
            inFlight = shared;
            return shared;
        }
    }

    private void clearInFlight(AtomicReference<Single<TokenRefreshOutcome>> holder) {
        synchronized (lock) {
            if (inFlight == holder.get()) {
                inFlight = null;
            }
        }
    }

    private Single<TokenRefreshOutcome> performRefresh(String refreshToken, long startedGeneration) {
        return Single.fromCallable(() -> authApi.get().refresh(new RefreshRequest(refreshToken)).execute())
                .flatMap(response -> handleResponse(response, startedGeneration))
                .onErrorResumeNext(error -> error instanceof IOException
                        ? Single.just(TokenRefreshOutcome.TransientFailure.INSTANCE)
                        : Single.error(error));
    }

    private Single<TokenRefreshOutcome> handleResponse(
            Response<AuthResponse> response,
            long startedGeneration) throws IOException {
        if (generation.get() != startedGeneration) {
            return Single.just(TokenRefreshOutcome.NoRefreshToken.INSTANCE);
        }
        AuthResponse body = response.body();
        if (response.isSuccessful() && body != null) {
            long expiresAt = System.currentTimeMillis() + body.getAccessTokenExpiresIn() * 1_000L;
            return saveIfCurrent(startedGeneration, body, expiresAt)
                    .andThen(Single.fromCallable(() -> generation.get() == startedGeneration
                            ? new TokenRefreshOutcome.FreshToken(body.getAccessToken())
                            : TokenRefreshOutcome.NoRefreshToken.INSTANCE));
        }
        String rawError = response.errorBody() == null ? null : response.errorBody().string();
        String errorCode = ErrorResponse.errorCodeOf(rawError);
        if (TokenRefreshRules.isDefinitiveRejection(response.code(), errorCode)) {
            return clear()
                    .andThen(Single.just(TokenRefreshOutcome.Rejected.INSTANCE));
        }
        return Single.just(TokenRefreshOutcome.TransientFailure.INSTANCE);
    }

    private Completable saveIfCurrent(long startedGeneration, AuthResponse body, long expiresAt) {
        return Completable.defer(() -> {
            synchronized (lock) {
                if (generation.get() != startedGeneration) {
                    return Completable.complete();
                }
                tokenStore.saveSession(body.getAccessToken(), expiresAt, body.getRefreshToken(),
                        new RecallWindowConfig(body.getRecallWindowSeconds(),
                                body.getGroupAdminMayRecallAfterWindow()))
                        .blockingAwait();
                return Completable.complete();
            }
        });
    }

    private static Optional<String> freshToken(TokenRefreshOutcome outcome) {
        return outcome instanceof TokenRefreshOutcome.FreshToken
                ? Optional.of(((TokenRefreshOutcome.FreshToken) outcome).getAccessToken())
                : Optional.empty();
    }
}
