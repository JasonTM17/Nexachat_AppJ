package com.nexachat.feature.invite;

import androidx.annotation.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Retrofit implementation that maps HTTP failures to safe, typed invite states. */
public final class RetrofitInviteRepository implements InviteRepository {
    private final InviteApi api;

    public RetrofitInviteRepository(@NonNull InviteApi api) {
        this.api = api;
    }

    @NonNull @Override
    public Single<Optional<InviteLinkResponse>> getActiveLink(@NonNull String conversationId) {
        return request(() -> api.getActiveLink(conversationId))
                .map(Optional::of)
                .onErrorResumeNext(error -> isFailure(error, InviteFailure.UNAVAILABLE)
                        ? Single.just(Optional.empty()) : Single.error(error));
    }

    @NonNull @Override
    public Single<InviteLinkResponse> createOrRotateLink(
            @NonNull String conversationId, @NonNull InviteLinkRequest request) {
        return request(() -> api.createOrRotateLink(conversationId, request));
    }

    @NonNull @Override
    public Single<InviteLinkResponse> updateApproval(
            @NonNull String conversationId, boolean requiresApproval) {
        return request(() -> api.updateApproval(
                conversationId, new InviteApprovalRequest(requiresApproval)));
    }

    @NonNull @Override
    public Completable revokeLink(@NonNull String conversationId) {
        return complete(() -> api.revokeLink(conversationId));
    }

    @NonNull @Override
    public Single<List<JoinRequestResponse>> listPendingRequests(@NonNull String conversationId) {
        return request(() -> api.listJoinRequests(conversationId, "PENDING"));
    }

    @NonNull @Override
    public Completable approveRequest(@NonNull String conversationId, @NonNull String requestId) {
        return complete(() -> api.approveJoinRequest(conversationId, requestId));
    }

    @NonNull @Override
    public Completable rejectRequest(@NonNull String conversationId, @NonNull String requestId) {
        return complete(() -> api.rejectJoinRequest(conversationId, requestId));
    }

    @NonNull @Override
    public Single<InvitePreviewResponse> loadPreview(@NonNull String token) {
        return request(() -> api.loadPreview(token));
    }

    @NonNull @Override
    public Single<JoinResponse> join(@NonNull String token) {
        return request(() -> api.join(token));
    }

    @NonNull @Override
    public Single<Optional<JoinRequestResponse>> getMyJoinRequest(@NonNull String conversationId) {
        return request(() -> api.getMyJoinRequest(conversationId))
                .map(Optional::of)
                .onErrorResumeNext(error -> isFailure(error, InviteFailure.UNAVAILABLE)
                        ? Single.just(Optional.empty()) : Single.error(error));
    }

    private static <T> Single<T> request(Callable<Call<T>> callFactory) {
        return send(callFactory).flatMap(response -> {
            T body = response.body();
            return body == null
                    ? Single.error(new InviteApiException(InviteFailure.UNKNOWN))
                    : Single.just(body);
        });
    }

    private static <T> Completable complete(Callable<Call<T>> callFactory) {
        return send(callFactory).ignoreElement();
    }

    private static <T> Single<Response<T>> send(Callable<Call<T>> callFactory) {
        return Single.create(emitter -> {
            final Call<T> call;
            try {
                call = callFactory.call();
            } catch (Exception failure) {
                emitter.onError(safeFailure(failure));
                return;
            }
            emitter.setCancellable(call::cancel);
            call.enqueue(new Callback<>() {
                @Override
                public void onResponse(@NonNull Call<T> ignored, @NonNull Response<T> response) {
                    if (emitter.isDisposed()) {
                        return;
                    }
                    if (response.isSuccessful()) {
                        emitter.onSuccess(response);
                    } else {
                        emitter.onError(failureForStatus(response.code()));
                    }
                }

                @Override
                public void onFailure(@NonNull Call<T> ignored, @NonNull Throwable failure) {
                    if (!emitter.isDisposed()) {
                        emitter.onError(safeFailure(failure));
                    }
                }
            });
        });
    }

    private static Throwable safeFailure(Throwable failure) {
        if (failure instanceof InviteApiException) {
            return failure;
        }
        if (failure instanceof IOException) {
            return new InviteApiException(InviteFailure.OFFLINE);
        }
        return new InviteApiException(InviteFailure.UNKNOWN);
    }

    private static InviteApiException failureForStatus(int status) {
        return switch (status) {
            case 400, 409 -> new InviteApiException(InviteFailure.VALIDATION);
            case 401 -> new InviteApiException(InviteFailure.SIGN_IN_REQUIRED);
            case 403 -> new InviteApiException(InviteFailure.NOT_ALLOWED);
            case 404 -> new InviteApiException(InviteFailure.UNAVAILABLE);
            case 429 -> new InviteApiException(InviteFailure.RATE_LIMITED);
            default -> new InviteApiException(InviteFailure.UNKNOWN);
        };
    }

    private static boolean isFailure(Throwable failure, InviteFailure expected) {
        return failure instanceof InviteApiException apiFailure
                && apiFailure.getFailure() == expected;
    }
}
