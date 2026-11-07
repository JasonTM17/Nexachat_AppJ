package com.nexachat.core.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.storage.TokenStore;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import kotlin.coroutines.Continuation;

/**
 * The only refresh-token rotation path. 401 recovery and splash restore share
 * one mutex inside {@link TokenRefresherCore}.
 */
@Singleton
public final class TokenRefresher implements TokenRefresherApi {

    private final TokenRefresherCore core;

    @Inject
    public TokenRefresher(
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull Provider<AuthApi> authApi) {
        this.core = new TokenRefresherCore(
                Objects.requireNonNull(tokenStore, "tokenStore"),
                Objects.requireNonNull(sessionEvents, "sessionEvents"),
                Objects.requireNonNull(authApi, "authApi"));
    }

    @Override
    public Object refresh(@NonNull Continuation<? super TokenRefreshOutcome> completion) {
        return core.refresh(completion);
    }

    @Override
    public Object refreshIfNeeded(
            @Nullable String failedAccessToken,
            @NonNull Continuation<? super TokenRefreshOutcome> completion) {
        return core.refreshIfNeeded(failedAccessToken, completion);
    }
}
