package com.nexachat.feature.auth;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;

import io.reactivex.rxjava3.core.Single;

/**
 * Session-restore decision used by {@link SplashViewModel}.
 *
 * <p>The caller supplies the stored-session validity probe and refresh call as
 * {@link Single}s. Threading is the caller's responsibility; the upstream
 * repository calls already run on {@code Schedulers.io()}.
 */
public final class SplashSession {

    private SplashSession() {
    }

    /**
     * Restores the splash destination.
     *
     * <p>When no usable refresh token is stored, navigates to login without calling
     * {@code refresh}. Otherwise a successful refresh (or a transient network failure)
     * navigates to the inbox; any other failure navigates to login.
     *
     * @param hasValidRefresh one-shot probe for a decryptable active session
     * @param hasStoredRefreshCredential one-shot probe for stored credentials after reauth closure
     * @param refresh one-shot token refresh call
     * @return the navigation event to emit
     */
    public static Single<SplashNavEvent> restoreDestination(
            Single<Boolean> hasValidRefresh,
            Single<Boolean> hasStoredRefreshCredential,
            Single<Result<Boolean>> refresh) {
        return hasValidRefresh.flatMap(hasValid -> {
            if (!Boolean.TRUE.equals(hasValid)) {
                return hasStoredRefreshCredential.map(stored ->
                        new SplashNavEvent.ToLogin(Boolean.TRUE.equals(stored)));
            }
            return refresh.map(SplashSession::toDestination);
        });
    }

    private static SplashNavEvent toDestination(Result<Boolean> result) {
        if (result instanceof Result.Success<?>) {
            return SplashNavEvent.ToInbox.INSTANCE;
        }
        if (result instanceof Result.Failure<?> failure
                && failure.getError() instanceof ApiError.Network) {
            return SplashNavEvent.ToInbox.INSTANCE;
        }
        if (result instanceof Result.Failure<?> failure
                && failure.getError() instanceof ApiError.SessionExpired) {
            return new SplashNavEvent.ToLogin(true);
        }
        return new SplashNavEvent.ToLogin(false);
    }
}
