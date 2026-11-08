package com.nexachat.core.media;

import androidx.annotation.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Marks cacheable attachment bytes as authorization-scoped and rejects API JSON/error caching. */
public final class MediaCacheResponseInterceptor implements Interceptor {

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);
        if (!MediaCacheConfig.isAttachmentContentGet(request)) {
            return noStore(response);
        }
        if (response.code() != 304
                && (!response.isSuccessful()
                        || !MediaCacheConfig.isMediaContentType(
                                response.header("Content-Type")))) {
            return noStore(response);
        }

        List<String> varyValues = response.headers("Vary");
        List<String> values = new ArrayList<>(varyValues);
        boolean hasAuthorization = false;
        for (String varyValue : varyValues) {
            for (String name : varyValue.split(",")) {
                String normalized = name.trim().toLowerCase(Locale.ROOT);
                if ("*".equals(normalized)) {
                    return noStore(response);
                }
                if ("authorization".equals(normalized)) {
                    hasAuthorization = true;
                }
            }
        }
        if (!hasAuthorization) {
            values.add("Authorization");
        }
        return response.newBuilder().header("Vary", String.join(", ", values)).build();
    }

    private static Response noStore(Response response) {
        return response.newBuilder().header("Cache-Control", "no-store").build();
    }
}
