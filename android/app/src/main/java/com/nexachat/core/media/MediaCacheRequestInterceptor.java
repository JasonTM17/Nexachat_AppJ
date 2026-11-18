package com.nexachat.core.media;

import androidx.annotation.NonNull;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Forces {@code Cache-Control: no-store} on every request so no response body —
 * in particular authenticated attachment content — is ever written to a disk
 * cache. The client no longer carries an OkHttp {@code Cache}; this header is
 * the guard that keeps it that way if a cache is ever re-introduced.
 */
public final class MediaCacheRequestInterceptor implements Interceptor {

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request().newBuilder()
                .header("Cache-Control", "no-store")
                .build();
        return chain.proceed(request);
    }
}
