package com.nexachat.feature.label;

import androidx.annotation.NonNull;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.SessionOperationTracker;
import com.nexachat.core.network.ApiResult;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import retrofit2.Call;

/** REST label operations and caller-scoped assignment cache updates. */
@Singleton
public final class RetrofitLabelRepository implements LabelRepository {
    private final LabelApi api;
    private final ConversationDao conversationDao;
    private final ConversationRepository conversationRepository;
    private final SessionOperationTracker operationTracker;

    @Inject
    public RetrofitLabelRepository(
            LabelApi api,
            ConversationDao conversationDao,
            ConversationRepository conversationRepository,
            SessionOperationTracker operationTracker) {
        this.api = api;
        this.conversationDao = conversationDao;
        this.conversationRepository = conversationRepository;
        this.operationTracker = operationTracker;
    }

    /** Preserves the local repository test constructor. */
    public RetrofitLabelRepository(
            LabelApi api,
            ConversationDao conversationDao,
            ConversationRepository conversationRepository) {
        this(api, conversationDao, conversationRepository, new SessionOperationTracker());
    }

    @Override
    public Single<List<ConversationLabel>> listLabels() {
        return operationTracker.track(requiredBody(api.listLabels()).map(rows -> {
            List<ConversationLabel> labels = new ArrayList<>(rows.size());
            for (LabelDto row : rows) labels.add(row.toLabel());
            return List.copyOf(labels);
        }));
    }

    @Override
    public Single<ConversationLabel> createLabel(String name, LabelColor color) {
        return operationTracker.track(requiredBody(
                api.createLabel(new LabelWriteRequest(name, color.getId())))
                .map(LabelDto::toLabel));
    }

    @Override
    public Single<ConversationLabel> updateLabel(String labelId, String name, LabelColor color) {
        return operationTracker.track(requiredBody(api.updateLabel(
                        labelId, new LabelWriteRequest(name, color.getId())))
                .map(LabelDto::toLabel));
    }

    @Override public Completable deleteLabel(String labelId) {
        return operationTracker.track(emptyBody(api.deleteLabel(labelId)));
    }

    @Override public Completable assignLabel(String conversationId, String labelId) {
        return operationTracker.track(emptyBody(api.assignLabel(conversationId, labelId)));
    }

    @Override public Completable unassignLabel(String conversationId, String labelId) {
        return operationTracker.track(emptyBody(api.unassignLabel(conversationId, labelId)));
    }

    @Override
    public Single<Set<String>> filterConversationIds(String labelId) {
        return operationTracker.track(conversationRepository.refreshInboxByLabel(labelId).flatMap(result -> {
            if (result instanceof Result.Success<?> success) {
                @SuppressWarnings("unchecked")
                Set<String> ids = (Set<String>) success.getValue();
                return Single.just(ids);
            }
            ApiError error = ((Result.Failure<?>) result).getError();
            if (error instanceof ApiError.Network) {
                return Single.error(new java.io.IOException("Inbox filter request failed"));
            }
            if (error instanceof ApiError.RateLimited) {
                return Single.error(new LabelApiException(429, "RATE_LIMITED"));
            }
            String code = error instanceof ApiError.Unknown unknown ? unknown.getCode() : null;
            int status = switch (code == null ? "" : code) {
                case "VALIDATION_FAILED" -> 400;
                case "CONFLICT" -> 409;
                case "RATE_LIMITED" -> 429;
                default -> 0;
            };
            return Single.error(new LabelApiException(status, code));
        }));
    }

    @Override
    public Single<List<String>> cachedLabelIds(String conversationId) {
        return conversationDao.byId(conversationId)
                .map(ConversationEntity::getLabelIds)
                .defaultIfEmpty(List.of());
    }

    @Override
    public Completable cacheLabelIds(String conversationId, List<String> labelIds) {
        return operationTracker.track(
                conversationDao.updateLabelIds(conversationId, LabelRules.toCsv(labelIds)));
    }

    @Override
    public Completable removeLabelFromCache(String labelId) {
        return operationTracker.track(conversationDao.removeLabelIdFromAll(labelId));
    }

    private <T> Single<T> requiredBody(@NonNull Call<T> call) {
        return ApiResult.safeApiCall(call).flatMap(result -> {
            if (result instanceof ApiResult.Success<?> success) {
                Object body = success.getBody();
                if (body == null) {
                    return Single.error(new LabelApiException(200, "EMPTY_RESPONSE"));
                }
                @SuppressWarnings("unchecked")
                T value = (T) body;
                return Single.just(value);
            }
            if (result instanceof ApiResult.HttpError<?> http) {
                return Single.error(new LabelApiException(http.getStatus(), http.getErrorCode()));
            }
            return Single.error(((ApiResult.NetworkError<?>) result).getCause());
        });
    }

    private Completable emptyBody(@NonNull Call<?> call) {
        return ApiResult.safeApiCall(call).flatMapCompletable(result -> {
            if (result instanceof ApiResult.Success<?>) return Completable.complete();
            if (result instanceof ApiResult.HttpError<?> http) {
                return Completable.error(
                        new LabelApiException(http.getStatus(), http.getErrorCode()));
            }
            return Completable.error(((ApiResult.NetworkError<?>) result).getCause());
        });
    }
}
