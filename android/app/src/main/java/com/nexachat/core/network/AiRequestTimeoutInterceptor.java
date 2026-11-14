package com.nexachat.core.network;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * AI summary and ask requests may run the server's whole model chain (primary, fallback and a
 * local model, each with its own timeout) before answering, which far outlasts the default read
 * timeout. Those calls wait for the server instead of failing with a network error while the
 * server still finishes, caches the result and spends the caller's rate-limit slot.
 */
public final class AiRequestTimeoutInterceptor implements Interceptor {

    /** Longer than the server's worst sequential model chain (30 s + 30 s + 60 s). */
    static final int AI_READ_TIMEOUT_SECONDS = 130;

    static boolean isAiRequest(@NonNull String method, @NonNull String path) {
        if (!"POST".equals(method) || !path.contains("/api/v1/conversations/")) {
            return false;
        }
        return path.endsWith("/summary") || path.endsWith("/ask");
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        if (isAiRequest(chain.request().method(), chain.request().url().encodedPath())) {
            return chain.withReadTimeout(AI_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .proceed(chain.request());
        }
        return chain.proceed(chain.request());
    }
}