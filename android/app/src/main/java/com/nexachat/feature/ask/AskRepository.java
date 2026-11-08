package com.nexachat.feature.ask;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Cancellable ask API boundary. Answers are returned to the caller and never cached. */
public final class AskRepository {
    private final AskApi api;

    public AskRepository(@NonNull AskApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @NonNull
    public Cancellable ask(
            @NonNull String conversationId,
            @NonNull AskRequest request,
            @NonNull ResultCallback callback) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callback, "callback");

        String question = request.getQuestion().trim();
        if (conversationId.trim().isEmpty() || question.isEmpty() || question.length() > 500) {
            callback.onError(new AskErrorMapping(AskError.VALIDATION, null));
            return RequestHandle.empty();
        }

        final Call<AskResponse> call;
        try {
            call = api.ask(conversationId, new AskRequest(question, request.getLanguage()));
        } catch (RuntimeException failure) {
            callback.onError(AskErrorMapper.fromFailure(failure));
            return RequestHandle.empty();
        }

        RequestHandle handle = new RequestHandle(call);
        call.enqueue(new Callback<>() {
            @Override
            public void onResponse(Call<AskResponse> ignored, Response<AskResponse> response) {
                if (handle.isCancelled()) {
                    return;
                }
                if (response.isSuccessful() && response.body() != null) {
                    callback.onSuccess(response.body());
                    return;
                }
                if (response.isSuccessful()) {
                    callback.onError(new AskErrorMapping(AskError.UNKNOWN, null));
                    return;
                }
                callback.onError(AskErrorMapper.fromHttp(
                        response.code(), readBody(response.errorBody())));
            }

            @Override
            public void onFailure(Call<AskResponse> ignored, Throwable failure) {
                if (!handle.isCancelled()) {
                    callback.onError(AskErrorMapper.fromFailure(failure));
                }
            }
        });
        return handle;
    }

    @Nullable
    private static String readBody(@Nullable ResponseBody body) {
        if (body == null) {
            return null;
        }
        try {
            return body.string();
        } catch (IOException ignored) {
            return null;
        }
    }

    public interface ResultCallback {
        void onSuccess(@NonNull AskResponse response);

        void onError(@NonNull AskErrorMapping error);
    }

    public interface Cancellable {
        void cancel();
    }

    private static final class RequestHandle implements Cancellable {
        private final Call<?> call;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private RequestHandle(@Nullable Call<?> call) {
            this.call = call;
        }

        static RequestHandle empty() {
            return new RequestHandle(null);
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true) && call != null) {
                call.cancel();
            }
        }

        private boolean isCancelled() {
            return cancelled.get();
        }
    }
}
