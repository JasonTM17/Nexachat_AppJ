package com.nexachat.feature.scheduled;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.ErrorResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Retrofit-backed scheduled-message repository. Server error bodies are never
 * surfaced to the UI; {@code sendAt} arrives ascending from the server and is
 * re-sorted defensively.
 */
public final class RetrofitScheduledMessageRepository implements ScheduledMessageRepository {

    private static final Comparator<ScheduledMessage> BY_SEND_AT =
            Comparator.comparing(ScheduledMessage::getSendAt);

    private final ScheduledMessageApi api;

    public RetrofitScheduledMessageRepository(@NonNull ScheduledMessageApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public Cancellable load(@NonNull String conversationId, @NonNull ListCallback callback) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(callback, "callback");
        final Call<List<ScheduledMessageDto>> call;
        try {
            call = api.list(conversationId);
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        call.enqueue(new Callback<List<ScheduledMessageDto>>() {
            @Override
            public void onResponse(
                    @NonNull Call<List<ScheduledMessageDto>> ignored,
                    @NonNull Response<List<ScheduledMessageDto>> response) {
                if (cancelled.get()) {
                    return;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    callback.onFailure(mapFailure(response.code(), response.errorBody()));
                    return;
                }
                List<ScheduledMessage> messages = new ArrayList<>();
                for (ScheduledMessageDto dto : response.body()) {
                    ScheduledMessage message = dto == null ? null : dto.toDomain();
                    if (message != null) {
                        messages.add(message);
                    }
                }
                messages.sort(BY_SEND_AT);
                callback.onSuccess(Collections.unmodifiableList(messages));
            }

            @Override
            public void onFailure(
                    @NonNull Call<List<ScheduledMessageDto>> ignored, @NonNull Throwable failure) {
                if (!cancelled.get()) {
                    callback.onFailure(failure instanceof IOException
                            ? Failure.OFFLINE : Failure.UNKNOWN);
                }
            }
        });
        return () -> {
            if (cancelled.compareAndSet(false, true)) {
                call.cancel();
            }
        };
    }

    @Override
    public Cancellable create(
            @NonNull String conversationId,
            @NonNull ScheduledMessageDraft draft,
            @NonNull String clientMessageId,
            @NonNull ItemCallback callback) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(clientMessageId, "clientMessageId");
        ScheduledMessageDraft normalized = draft.normalized();
        final Call<ScheduledMessageDto> call;
        try {
            call = api.create(conversationId, new ScheduledMessageRequest(
                    clientMessageId,
                    normalized.getBody(),
                    ScheduledMessageTime.toUtcIso(normalized.getSendAt())));
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        call.enqueue(new Callback<ScheduledMessageDto>() {
            @Override
            public void onResponse(
                    @NonNull Call<ScheduledMessageDto> ignored,
                    @NonNull Response<ScheduledMessageDto> response) {
                if (cancelled.get()) {
                    return;
                }
                ScheduledMessage message = response.body() == null
                        ? null : response.body().toDomain();
                if (!response.isSuccessful() || message == null) {
                    callback.onFailure(response.isSuccessful()
                            ? Failure.UNKNOWN
                            : mapFailure(response.code(), response.errorBody()));
                } else {
                    callback.onSuccess(message);
                }
            }

            @Override
            public void onFailure(
                    @NonNull Call<ScheduledMessageDto> ignored, @NonNull Throwable failure) {
                if (!cancelled.get()) {
                    callback.onFailure(failure instanceof IOException
                            ? Failure.OFFLINE : Failure.UNKNOWN);
                }
            }
        });
        return () -> {
            if (cancelled.compareAndSet(false, true)) {
                call.cancel();
            }
        };
    }

    @Override
    public Cancellable delete(
            @NonNull String conversationId,
            @NonNull String scheduledId,
            @NonNull DeleteCallback callback) {
        final Call<Void> call;
        try {
            call = api.delete(conversationId, scheduledId);
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        call.enqueue(new Callback<Void>() {
            @Override
            public void onResponse(@NonNull Call<Void> ignored, @NonNull Response<Void> response) {
                if (cancelled.get()) {
                    return;
                }
                if (response.isSuccessful()) {
                    callback.onSuccess();
                } else {
                    callback.onFailure(mapFailure(response.code(), response.errorBody()));
                }
            }

            @Override
            public void onFailure(@NonNull Call<Void> ignored, @NonNull Throwable failure) {
                if (!cancelled.get()) {
                    callback.onFailure(failure instanceof IOException
                            ? Failure.OFFLINE : Failure.UNKNOWN);
                }
            }
        });
        return () -> {
            if (cancelled.compareAndSet(false, true)) {
                call.cancel();
            }
        };
    }

    static Failure mapFailure(int status, @Nullable ResponseBody errorBody) {
        String code = ErrorResponse.errorCodeOf(readBody(errorBody));
        if (status == 429 || "RATE_LIMITED".equals(code)) {
            return Failure.RATE_LIMITED;
        }
        if ("NOT_MEMBER".equals(code)) {
            return Failure.NOT_MEMBER;
        }
        if ("E2EE_REQUIRED".equals(code) || "E2EE_NOT_ENABLED".equals(code)) {
            return Failure.E2EE;
        }
        if ("SCHEDULED_LIMIT".equals(code)) {
            return Failure.LIMIT;
        }
        if ("DISPATCHING".equals(code)) {
            return Failure.DISPATCHING;
        }
        if ("SCHEDULED_INVALID".equals(code)) {
            return Failure.CONFLICT;
        }
        if ("NOT_FOUND".equals(code) || "CONVERSATION_NOT_FOUND".equals(code)) {
            return Failure.NOT_FOUND;
        }
        if ("VALIDATION_FAILED".equals(code) || status == 400) {
            return Failure.VALIDATION;
        }
        if (status == 401 || status == 403) {
            return Failure.FORBIDDEN;
        }
        if (status == 404) {
            return Failure.NOT_FOUND;
        }
        if (status == 409) {
            return Failure.CONFLICT;
        }
        return Failure.UNKNOWN;
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
}
