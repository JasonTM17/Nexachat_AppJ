package com.nexachat.feature.archive;

import androidx.annotation.NonNull;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Retrofit-backed archive repository. Server error bodies are never surfaced to the UI. */
public final class RetrofitArchiveRepository implements ArchiveRepository {

    private final ArchiveApi api;

    public RetrofitArchiveRepository(@NonNull ArchiveApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public Cancellable load(@NonNull ArchiveQuery query, @NonNull Callback callback) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(callback, "callback");

        final Call<ArchivePage> call;
        try {
            call = api.loadArchive(
                    query.getConversationId(),
                    query.getType().name(),
                    query.getSenderId(),
                    query.getFrom(),
                    query.getTo(),
                    query.getLimit(),
                    query.getCursor());
        } catch (RuntimeException failure) {
            callback.onFailure(Failure.UNKNOWN);
            return () -> { };
        }

        AtomicBoolean cancelled = new AtomicBoolean();
        call.enqueue(new retrofit2.Callback<ArchivePage>() {
            @Override
            public void onResponse(
                    @NonNull Call<ArchivePage> ignored,
                    @NonNull Response<ArchivePage> response) {
                if (cancelled.get()) {
                    return;
                }
                if (response.code() == 403) {
                    callback.onFailure(Failure.FORBIDDEN);
                } else if (!response.isSuccessful() || response.body() == null) {
                    callback.onFailure(Failure.UNKNOWN);
                } else {
                    callback.onSuccess(response.body());
                }
            }

            @Override
            public void onFailure(
                    @NonNull Call<ArchivePage> ignored,
                    @NonNull Throwable failure) {
                if (cancelled.get()) {
                    return;
                }
                callback.onFailure(failure instanceof IOException
                        ? Failure.OFFLINE
                        : Failure.UNKNOWN);
            }
        });
        return () -> {
            if (cancelled.compareAndSet(false, true)) {
                call.cancel();
            }
        };
    }
}
