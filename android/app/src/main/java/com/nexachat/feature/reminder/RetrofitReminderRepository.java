package com.nexachat.feature.reminder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.ErrorResponse;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.time.Instant;
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

/** Retrofit-backed reminder repository. Server error bodies are never surfaced to the UI. */
public final class RetrofitReminderRepository implements ReminderRepository {

    private static final Comparator<Reminder> BY_REMIND_AT =
            Comparator.comparing(Reminder::getRemindAt);
    private static final JsonAdapter<ErrorResponse> ERROR_ADAPTER =
            new Moshi.Builder().build().adapter(ErrorResponse.class);

    private final ReminderApi api;

    public RetrofitReminderRepository(@NonNull ReminderApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public Cancellable load(@NonNull String conversationId, @NonNull ListCallback callback) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(callback, "callback");
        final Call<List<ReminderDto>> call;
        try {
            call = api.list(conversationId);
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        call.enqueue(new Callback<List<ReminderDto>>() {
            @Override
            public void onResponse(
                    @NonNull Call<List<ReminderDto>> ignored,
                    @NonNull Response<List<ReminderDto>> response) {
                if (cancelled.get()) {
                    return;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    callback.onFailure(mapFailure(response.code(), response.errorBody()));
                    return;
                }
                List<Reminder> reminders = new ArrayList<>();
                for (ReminderDto dto : response.body()) {
                    Reminder reminder = dto == null ? null : dto.toDomain();
                    if (reminder != null) {
                        reminders.add(reminder);
                    }
                }
                reminders.sort(BY_REMIND_AT);
                callback.onSuccess(Collections.unmodifiableList(reminders));
            }

            @Override
            public void onFailure(@NonNull Call<List<ReminderDto>> ignored, @NonNull Throwable failure) {
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
            @NonNull ReminderDraft draft,
            @NonNull String idempotencyKey,
            @NonNull ItemCallback callback) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        ReminderDraft normalized = draft.normalized();
        final Call<ReminderDto> call;
        try {
            call = api.create(conversationId, idempotencyKey, new ReminderRequest(
                    normalized.getTitle(),
                    ReminderTime.toUtcIso(normalized.getRemindAt()),
                    normalized.getRepeat().wireName()));
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }
        return enqueueItem(call, callback);
    }

    @Override
    public Cancellable update(
            @NonNull String conversationId,
            @NonNull String reminderId,
            @Nullable String title,
            @Nullable Instant remindAt,
            @Nullable ReminderRepeat repeat,
            @NonNull ItemCallback callback) {
        ReminderRequest request = new ReminderRequest(
                title == null ? null : title.trim(),
                remindAt == null ? null : ReminderTime.toUtcIso(remindAt),
                repeat == null ? null : repeat.wireName());
        final Call<ReminderDto> call;
        try {
            call = api.update(conversationId, reminderId, request);
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }
        return enqueueItem(call, callback);
    }

    @Override
    public Cancellable delete(
            @NonNull String conversationId,
            @NonNull String reminderId,
            @NonNull DeleteCallback callback) {
        final Call<Void> call;
        try {
            call = api.delete(conversationId, reminderId);
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

    private static Cancellable enqueueItem(Call<ReminderDto> call, ItemCallback callback) {
        AtomicBoolean cancelled = new AtomicBoolean();
        call.enqueue(new Callback<ReminderDto>() {
            @Override
            public void onResponse(
                    @NonNull Call<ReminderDto> ignored, @NonNull Response<ReminderDto> response) {
                if (cancelled.get()) {
                    return;
                }
                Reminder reminder = response.body() == null ? null : response.body().toDomain();
                if (!response.isSuccessful() || reminder == null) {
                    callback.onFailure(response.isSuccessful()
                            ? Failure.UNKNOWN
                            : mapFailure(response.code(), response.errorBody()));
                } else {
                    callback.onSuccess(reminder);
                }
            }

            @Override
            public void onFailure(@NonNull Call<ReminderDto> ignored, @NonNull Throwable failure) {
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
        String rawBody = readBody(errorBody);
        String code = ErrorResponse.errorCodeOf(rawBody);
        if (status == 400 && "VALIDATION_FAILED".equals(code) && hasLimitDetail(rawBody)) {
            return Failure.LIMIT;
        }
        if (status == 429 || "RATE_LIMITED".equals(code)) {
            return Failure.RATE_LIMITED;
        }
        if ("NOT_MEMBER".equals(code)) {
            return Failure.NOT_MEMBER;
        }
        if ("NOT_ADMIN".equals(code)) {
            return Failure.NOT_ADMIN;
        }
        if ("FORBIDDEN".equals(code)) {
            return Failure.FORBIDDEN;
        }
        if ("NOT_FOUND".equals(code) || "CONVERSATION_NOT_FOUND".equals(code)) {
            return Failure.NOT_FOUND;
        }
        if ("IDEMPOTENCY_KEY_REUSED".equals(code)) {
            return Failure.CONFLICT;
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
        return Failure.UNKNOWN;
    }

    private static boolean hasLimitDetail(@Nullable String rawBody) {
        if (rawBody == null) {
            return false;
        }
        try {
            ErrorResponse error = ERROR_ADAPTER.fromJson(rawBody);
            return error != null
                    && error.getError() != null
                    && error.getError().getDetails() != null
                    && error.getError().getDetails().get("limit") != null;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
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
