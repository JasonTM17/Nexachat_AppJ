package com.nexachat.core.media;

import androidx.annotation.NonNull;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Prevents API JSON and all non-attachment requests from reading or populating OkHttp cache. */
public final class MediaCacheRequestInterceptor implements Interceptor {

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        if (!MediaCacheConfig.isAttachmentContentGet(request)) {
            request = request.newBuilder().header("Cache-Control", "no-store").build();
        }
        return chain.proceed(request);
    }
}
