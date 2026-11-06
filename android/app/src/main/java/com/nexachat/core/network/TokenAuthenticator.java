package com.nexachat.core.network;

import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import okhttp3.Authenticator;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

/**
 * OkHttp 401 recovery. Token rotation stays in the shared {@link TokenRefresher} path.
 *
 * <p>Public auth-path 401s are final, and chained retries are capped at
 * {@link #MAX_ATTEMPTS}. A fresh token retries the request; every other outcome
 * leaves it failed so session handling remains with {@link TokenRefresher}.
 */
@Singleton
public final class TokenAuthenticator implements Authenticator {

    private static final int MAX_ATTEMPTS = 3;

    private final TokenRefresher tokenRefresher;

    @Inject
    public TokenAuthenticator(TokenRefresher tokenRefresher) {
        this.tokenRefresher = tokenRefresher;
    }

    @Override
    public Request authenticate(Route route, Response response) throws IOException {
        Request failedRequest = response.request();

        if (AuthApi.isPublicAuthPath(failedRequest.url().encodedPath())) {
            return null;
        }
        if (responseCount(response) >= MAX_ATTEMPTS) {
            return null;
        }

        String failedAccessToken = bearerToken(failedRequest);
        Function2<CoroutineScope, Continuation<? super TokenRefresher.Outcome>, Object> refresh =
                (scope, continuation) ->
                        tokenRefresher.refreshIfNeeded(failedAccessToken, continuation);

        final TokenRefresher.Outcome outcome;
        try {
            outcome = (TokenRefresher.Outcome) BuildersKt.runBlocking(
                    EmptyCoroutineContext.INSTANCE,
                    refresh);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("token refresh interrupted", interrupted);
        }

        if (outcome instanceof TokenRefresher.Outcome.FreshToken) {
            String accessToken =
                    ((TokenRefresher.Outcome.FreshToken) outcome).getAccessToken();
            return retryWith(failedRequest, accessToken);
        }
        return null;
    }

    private Request retryWith(Request request, String accessToken) {
        return request.newBuilder()
                .header(
                        AuthInterceptor.HEADER_AUTHORIZATION,
                        AuthInterceptor.BEARER_PREFIX + accessToken)
                .build();
    }

    private String bearerToken(Request request) {
        String authorization = request.header(AuthInterceptor.HEADER_AUTHORIZATION);
        if (authorization == null) {
            return null;
        }
        return authorization.startsWith(AuthInterceptor.BEARER_PREFIX)
                ? authorization.substring(AuthInterceptor.BEARER_PREFIX.length())
                : authorization;
    }

    private int responseCount(Response response) {
        int count = 1;
        Response prior = response.priorResponse();
        while (prior != null) {
            count++;
            prior = prior.priorResponse();
        }
        return count;
    }
}
