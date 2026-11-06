package com.nexachat.core.network;

import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Adds the stored bearer token except on the public auth endpoints. */
@Singleton
public final class AuthInterceptor implements Interceptor {

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    private final TokenStore tokenStore;

    @Inject
    public AuthInterceptor(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public Response intercept(Interceptor.Chain chain) throws IOException {
        Request request = chain.request();
        if (AuthApi.isPublicAuthPath(request.url().encodedPath())) {
            return chain.proceed(request);
        }

        Function2<CoroutineScope, Continuation<? super Tokens>, Object> readTokens =
                (scope, continuation) -> tokenStore.readTokens(continuation);
        final Tokens tokens;
        try {
            tokens = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, readTokens);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("token read interrupted", interrupted);
        }
        String accessToken = tokens == null ? null : tokens.getAccessToken();
        Request authenticated = accessToken == null
                ? request
                : request.newBuilder()
                        .header(HEADER_AUTHORIZATION, BEARER_PREFIX + accessToken)
                        .build();
        return chain.proceed(authenticated);
    }
}
