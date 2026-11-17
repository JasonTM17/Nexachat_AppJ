package com.nexachat.core.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Callback-style execution of a Retrofit call with a small failure model that keeps the HTTP
 * status, the server error code and a rate-limit hint. Callbacks arrive on the Retrofit callback
 * executor (the main thread on Android). Server error bodies are never surfaced to the UI.
 */
public final class ApiCalls {

    private static volatile JsonAdapter<Map<String, Object>> mapAdapter;

    private ApiCalls() { }

    /** Result of a successful call; {@code body} is null for an empty 2xx response. */
    public interface ResultCallback<T> {
        void onSuccess(@Nullable T body, int status);

        void onFailure(@NonNull Failure failure);
    }

    /** Cancels an in-flight call; safe to call repeatedly. */
    public interface Cancellable {
        void cancel();
    }

    /** Why a call failed. {@code status} is 0 when no HTTP response arrived. */
    public static final class Failure {
        private final int status;
        @Nullable private final String code;
        private final int retryAfterSeconds;
        private final boolean offline;

        public Failure(int status, @Nullable String code, int retryAfterSeconds, boolean offline) {
            this.status = status;
            this.code = code;
            this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
            this.offline = offline;
        }

        public static Failure offline() {
            return new Failure(0, null, 0, true);
        }

        public static Failure unknown() {
            return new Failure(0, null, 0, false);
        }

        public int getStatus() { return status; }

        @Nullable public String getCode() { return code; }

        /** Seconds the server asked the caller to wait; 0 when it gave no hint. */
        public int getRetryAfterSeconds() { return retryAfterSeconds; }

        public boolean isOffline() { return offline; }

        public boolean isRateLimited() {
            return status == 429 || "RATE_LIMITED".equals(code);
        }
    }

    @NonNull
    public static <T> Cancellable enqueue(@NonNull Call<T> call, @NonNull ResultCallback<T> callback) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(callback, "callback");
        AtomicBoolean cancelled = new AtomicBoolean();
        call.enqueue(new Callback<T>() {
            @Override
            public void onResponse(@NonNull Call<T> ignored, @NonNull Response<T> response) {
                if (cancelled.get()) {
                    return;
                }
                if (response.isSuccessful()) {
                    callback.onSuccess(response.body(), response.code());
                } else {
                    callback.onFailure(failureOf(response));
                }
            }

            @Override
            public void onFailure(@NonNull Call<T> ignored, @NonNull Throwable failure) {
                if (cancelled.get()) {
                    return;
                }
                callback.onFailure(failure instanceof IOException
                        ? Failure.offline()
                        : Failure.unknown());
            }
        });
        return () -> {
            if (cancelled.compareAndSet(false, true)) {
                call.cancel();
            }
        };
    }

    /** Builds the failure for a non-2xx response. */
    @NonNull
    public static Failure failureOf(@NonNull Response<?> response) {
        String raw = null;
        if (response.errorBody() != null) {
            try {
                raw = response.errorBody().string();
            } catch (IOException ignored) {
                raw = null;
            }
        }
        String header = response.headers().get("Retry-After");
        return parseFailure(response.code(), raw, header);
    }

    /**
     * Reads {@code error.code} and a retry hint from a standard error envelope. The hint comes
     * from {@code error.details.retryAfterSeconds}, {@code error.retryAfterSeconds}, a top level
     * {@code retryAfterSeconds} or the Retry-After header, in that order.
     */
    @NonNull
    public static Failure parseFailure(int status, @Nullable String body, @Nullable String retryAfterHeader) {
        String code = null;
        int retryAfter = 0;
        Map<String, Object> root = parseMap(body);
        if (root != null) {
            Object error = root.get("error");
            if (error instanceof Map<?, ?> errorMap) {
                Object rawCode = errorMap.get("code");
                if (rawCode instanceof String text) {
                    code = text;
                }
                Object details = errorMap.get("details");
                if (details instanceof Map<?, ?> detailMap) {
                    retryAfter = seconds(detailMap.get("retryAfterSeconds"));
                }
                if (retryAfter == 0) {
                    retryAfter = seconds(errorMap.get("retryAfterSeconds"));
                }
            }
            if (retryAfter == 0) {
                retryAfter = seconds(root.get("retryAfterSeconds"));
            }
        }
        if (retryAfter == 0 && retryAfterHeader != null) {
            try {
                retryAfter = Math.max(0, Integer.parseInt(retryAfterHeader.trim()));
            } catch (NumberFormatException ignored) {
                retryAfter = 0;
            }
        }
        return new Failure(status, code, retryAfter, false);
    }

    private static int seconds(@Nullable Object value) {
        if (value instanceof Number number) {
            double raw = number.doubleValue();
            return raw > 0 && raw < Integer.MAX_VALUE ? (int) Math.ceil(raw) : 0;
        }
        return 0;
    }

    @Nullable
    private static Map<String, Object> parseMap(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonAdapter<Map<String, Object>> adapter = mapAdapter;
            if (adapter == null) {
                adapter = new Moshi.Builder().build().adapter(
                        Types.newParameterizedType(Map.class, String.class, Object.class));
                mapAdapter = adapter;
            }
            return adapter.fromJson(json);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}
