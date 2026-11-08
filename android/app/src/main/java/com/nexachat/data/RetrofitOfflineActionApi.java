package com.nexachat.data;

import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.dto.PinRequest;
import com.nexachat.core.network.dto.ReactionRequest;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.ResponseBody;
import retrofit2.Call;

@Singleton
public final class RetrofitOfflineActionApi implements OfflineActionApi {
    private final MessageApi messageApi;

    @Inject
    public RetrofitOfflineActionApi(MessageApi messageApi) {
        this.messageApi = messageApi;
    }

    @Override
    public io.reactivex.rxjava3.core.Single<ApiResult<?>> dispatch(OfflineOutboxAction action) {
        if (OfflineOutboxAction.REACTION_ADD.equals(action.getActionType())
                || OfflineOutboxAction.REACTION_REMOVE.equals(action.getActionType())) {
            Call<?> call = OfflineOutboxAction.REACTION_ADD.equals(action.getActionType())
                    ? messageApi.addReaction(action.getMessageId(), action.getEmoji(), action.getIdempotencyKey())
                    : messageApi.removeReaction(action.getMessageId(), action.getEmoji(), action.getIdempotencyKey());
            return execute(call).flatMap(result -> {
                if (!isUnsupportedSetEndpoint(result)) {
                    return io.reactivex.rxjava3.core.Single.just(result);
                }
                if (action.desiredState() == action.getStateBefore()) {
                    // The desired state already matches the last known server state, so the
                    // unsupported set endpoint has nothing to change and toggling would invert it.
                    return io.reactivex.rxjava3.core.Single.just(new ApiResult.Success<>(null));
                }
                return execute(messageApi.toggleReaction(action.getMessageId(),
                        new ReactionRequest(action.getEmoji()), action.getIdempotencyKey()));
            });
        }
        if (OfflineOutboxAction.PIN.equals(action.getActionType())) {
            return execute(messageApi.pinMessage(action.getConversationId(),
                    new PinRequest(action.getMessageId()), action.getIdempotencyKey()));
        }
        if (OfflineOutboxAction.UNPIN.equals(action.getActionType())) {
            return execute(messageApi.unpinMessage(action.getConversationId(),
                    action.getMessageId(), action.getIdempotencyKey()));
        }
        return io.reactivex.rxjava3.core.Single.error(
                new IllegalArgumentException("unsupported offline action"));
    }

    private static boolean isUnsupportedSetEndpoint(ApiResult<?> result) {
        if (!(result instanceof ApiResult.HttpError<?> error)) return false;
        return error.getStatus() == 404 || error.getStatus() == 405;
    }

    private static <T> io.reactivex.rxjava3.core.Single<ApiResult<?>> execute(Call<T> call) {
        return ApiResult.safeApiCall(call).map(result -> {
            if (result instanceof ApiResult.Success<?> success
                    && success.getBody() instanceof ResponseBody body) {
                body.close();
            }
            return result;
        });
    }
}
