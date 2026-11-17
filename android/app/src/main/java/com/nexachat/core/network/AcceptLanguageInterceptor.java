package com.nexachat.core.network;

import java.io.IOException;
import java.util.Locale;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Stamps the visible app language on every request so the server can pick the
 * matching mail template. {@code Locale.getDefault()} is the source of truth:
 * {@code AppLocaleManager} sets it directly below API 33, and on 33+ the
 * framework applies {@code setApplicationLocales} to the process default.
 */
final class AcceptLanguageInterceptor implements Interceptor {
    static final String HEADER_ACCEPT_LANGUAGE = "Accept-Language";
    private static final String FALLBACK_TAG = "en";

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (request.header(HEADER_ACCEPT_LANGUAGE) != null) {
            return chain.proceed(request);
        }
        Request localized = request.newBuilder()
                .header(HEADER_ACCEPT_LANGUAGE, currentLanguageTag())
                .build();
        return chain.proceed(localized);
    }

    private static String currentLanguageTag() {
        String tag = Locale.getDefault().toLanguageTag();
        // toLanguageTag() renders Locale.ROOT as "und", which is not a usable
        // Accept-Language value.
        return tag.isEmpty() || "und".equals(tag) ? FALLBACK_TAG : tag;
    }
}
