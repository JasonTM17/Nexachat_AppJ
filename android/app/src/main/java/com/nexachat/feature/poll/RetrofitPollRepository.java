package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.ErrorResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Retrofit-backed poll repository. Server messages never reach the UI. */
public final class RetrofitPollRepository implements PollRepository {

    public static final int PAGE_SIZE = 50;
    public static final int CHANGES_PAGE_SIZE = 100;

    private final PollApi api;

    public RetrofitPollRepository(@NonNull PollApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public Cancellable load(
            @NonNull String conversationId,
            @Nullable String before,
            @NonNull PageCallback callback) {
        final Call<PollPageDto> call;
        try {
            call = api.list(conversationId, PAGE_SIZE, before);
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        call.enqueue(new Callback<PollPageDto>() {
            @Override
            public void onResponse(
                    @NonNull Call<PollPageDto> ignored, @NonNull Response<PollPageDto> response) {
                if (cancelled.get()) {
                    return;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    callback.onFailure(mapFailure(response.code(), response.errorBody()));
                } else {
                    callback.onSuccess(response.body().toDomain());
                }
            }

            @Override
            public void onFailure(@NonNull Call<PollPageDto> ignored, @NonNull Throwable failure) {
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
    public Cancellable changes(
            @NonNull String conversationId,
            long since,
            int limit,
            @NonNull ChangesCallback callback) {
        final Call<PollChangesDto> call;
        try {
            call = api.changes(conversationId, Math.max(0L, since), limit);
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        call.enqueue(new Callback<PollChangesDto>() {
            @Override
            public void onResponse(
                    @NonNull Call<PollChangesDto> ignored,
                    @NonNull Response<PollChangesDto> response) {
                if (cancelled.get()) {
                    return;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    callback.onFailure(mapFailure(response.code(), response.errorBody()));
                } else {
                    callback.onSuccess(response.body().toDomain());
                }
            }

            @Override
            public void onFailure(@NonNull Call<PollChangesDto> ignored, @NonNull Throwable failure) {
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
            @NonNull PollDraft draft,
            @NonNull ItemCallback callback) {
        PollDraft normalized = draft.normalized();
        CreatePollRequest request = new CreatePollRequest(
                normalized.getQuestion(),
                normalized.getOptions(),
                normalized.isMultipleChoice(),
                normalized.isAnonymous(),
                normalized.getClosesAt() == null ? null : PollTime.toUtcIso(normalized.getClosesAt()));
        final Call<PollDto> call;
        try {
            call = api.create(conversationId, request);
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }
        return enqueueItem(call, callback);
    }

    @Override
    public Cancellable vote(
            @NonNull String conversationId,
            @NonNull String pollId,
            @NonNull List<String> optionIds,
            @NonNull ItemCallback callback) {
        final Call<PollDto> call;
        try {
            call = api.vote(conversationId, pollId, new VoteRequest(List.copyOf(optionIds)));
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }
        return enqueueItem(call, callback);
    }

    @Override
    public Cancellable retractVote(
            @NonNull String conversationId,
            @NonNull String pollId,
            @NonNull ItemCallback callback) {
        final Call<PollDto> call;
        try {
            call = api.retractVote(conversationId, pollId);
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }
        return enqueueItem(call, callback);
    }

    @Override
    public Cancellable close(
            @NonNull String conversationId,
            @NonNull String pollId,
            @NonNull ItemCallback callback) {
        final Call<PollDto> call;
        try {
            call = api.close(conversationId, pollId);
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }
        return enqueueItem(call, callback);
    }

    @Override
    public Cancellable delete(
            @NonNull String conversationId,
            @NonNull String pollId,
            @NonNull DeleteCallback callback) {
        final Call<Void> call;
        try {
            call = api.delete(conversationId, pollId);
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

    private static Cancellable enqueueItem(Call<PollDto> call, ItemCallback callback) {
        AtomicBoolean cancelled = new AtomicBoolean();
        call.enqueue(new Callback<PollDto>() {
            @Override
            public void onResponse(
                    @NonNull Call<PollDto> ignored, @NonNull Response<PollDto> response) {
                if (cancelled.get()) {
                    return;
                }
                Poll poll = response.body() == null ? null : response.body().toDomain();
                if (!response.isSuccessful() || poll == null) {
                    callback.onFailure(response.isSuccessful()
                            ? Failure.UNKNOWN
                            : mapFailure(response.code(), response.errorBody()));
                } else {
                    callback.onSuccess(poll);
                }
            }

            @Override
            public void onFailure(@NonNull Call<PollDto> ignored, @NonNull Throwable failure) {
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
        if ("NOT_A_GROUP".equals(code)) {
            return Failure.NOT_A_GROUP;
        }
        if ("NOT_MEMBER".equals(code)) {
            return Failure.NOT_MEMBER;
        }
        if ("NOT_ADMIN".equals(code)) {
            return Failure.NOT_ADMIN;
        }
        if ("POLL_CLOSED".equals(code)) {
            return Failure.CLOSED;
        }
        if ("POLL_NOT_FOUND".equals(code) || "CONVERSATION_NOT_FOUND".equals(code)) {
            return Failure.NOT_FOUND;
        }
        if ("VALIDATION_FAILED".equals(code) || status == 400) {
            return Failure.VALIDATION;
        }
        if (status == 403) {
            return Failure.NOT_MEMBER;
        }
        if (status == 404) {
            return Failure.NOT_FOUND;
        }
        if (status == 409) {
            return Failure.CLOSED;
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
