package com.nexachat.core.network;

import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
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
        if (request.header(HEADER_AUTHORIZATION) != null) {
            return chain.proceed(request);
        }

        final Tokens tokens;
        try {
            tokens = tokenStore.readTokens().blockingGet();
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("token read interrupted", interrupted);
            }
            throw ex;
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
