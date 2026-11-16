package com.nexachat.feature.safety;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.database.StarDao;
import com.nexachat.core.database.StarredMessageEntity;
import com.nexachat.core.network.ApiResult;
import com.nexachat.data.SessionOperationTracker;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import retrofit2.Call;

/** REST safety calls plus the Room-backed star id set. */
@Singleton
public final class RetrofitSafetyRepository implements SafetyRepository {
    private final SafetyApi api;
    private final StarDao starDao;
    private final SessionOperationTracker operationTracker;

    @Inject
    public RetrofitSafetyRepository(
            SafetyApi api,
            StarDao starDao,
            SessionOperationTracker operationTracker) {
        this.api = api;
        this.starDao = starDao;
        this.operationTracker = operationTracker;
    }

    /** Preserves a lighter test constructor. */
    public RetrofitSafetyRepository(SafetyApi api, StarDao starDao) {
        this(api, starDao, new SessionOperationTracker());
    }

    @Override public Completable blockUser(String userId) {
        return operationTracker.track(emptyBody(api.blockUser(userId)));
    }

    @Override public Completable unblockUser(String userId) {
        return operationTracker.track(emptyBody(api.unblockUser(userId)));
    }

    @Override public Single<Boolean> blockStatus(String userId) {
        return operationTracker.track(requiredBody(api.blockStatus(userId))
                .map(BlockStatusDto::isBlocked));
    }

    @Override public Single<BlockedPage> listBlocked(int limit, String cursor) {
        return operationTracker.track(requiredBody(api.listBlocked(limit, cursor)).map(page -> {
            List<BlockedUser> items = new ArrayList<>(page.getItems().size());
            for (BlockedUserDto row : page.getItems()) {
                items.add(new BlockedUser(
                        row.getUserId(),
                        row.getDisplayName(),
                        row.getPhone(),
                        row.getAvatarUrl(),
                        row.getBlockedAt()));
            }
            return new BlockedPage(List.copyOf(items), page.getNextCursor());
        }));
    }

    @Override public Completable report(
            String targetType,
            String targetId,
            ReportReason reason,
            String details,
            String messageSnapshot) {
        String trimmedDetails = details == null || details.isBlank() ? null : details.trim();
        String trimmedSnapshot =
                messageSnapshot == null || messageSnapshot.isBlank()
                        ? null
                        : messageSnapshot.trim();
        return operationTracker.track(emptyBody(api.createReport(new ReportRequest(
                targetType, targetId, reason.name(), trimmedDetails, trimmedSnapshot))));
    }

    @Override public Completable starMessage(String messageId, String conversationId) {
        StarredMessageEntity row =
                new StarredMessageEntity(messageId, conversationId, System.currentTimeMillis());
        return operationTracker.track(starDao.upsert(row)
                .andThen(emptyBody(api.starMessage(messageId)))
                .doOnError(ignored -> starDao.delete(messageId).onErrorComplete().subscribe()));
    }

    @Override public Completable unstarMessage(String messageId, String conversationId) {
        return operationTracker.track(starDao.delete(messageId)
                .andThen(emptyBody(api.unstarMessage(messageId)))
                .doOnError(ignored -> starDao.upsert(new StarredMessageEntity(
                                messageId, conversationId, System.currentTimeMillis()))
                        .onErrorComplete()
                        .subscribe()));
    }

    @Override public Single<StarredPage> listStarred(int limit, String cursor) {
        return operationTracker.track(requiredBody(api.listStarred(limit, cursor)).map(page -> {
            List<StarredMessage> items = new ArrayList<>(page.getItems().size());
            for (StarredMessageDto row : page.getItems()) {
                if (row.getMessage() == null) {
                    continue;
                }
                items.add(new StarredMessage(row.getStarredAt(), row.getMessage()));
            }
            return new StarredPage(List.copyOf(items), page.getNextCursor());
        }));
    }

    @Override public Flowable<Set<String>> starredIds(String conversationId) {
        return starDao.starredIdsFor(conversationId)
                .map(ids -> Set.copyOf(new HashSet<>(ids)));
    }

    private <T> Single<T> requiredBody(@NonNull Call<T> call) {
        return ApiResult.safeApiCall(call).flatMap(result -> {
            if (result instanceof ApiResult.Success<?> success) {
                Object body = success.getBody();
                if (body == null) {
                    return Single.error(new SafetyApiException(200, "EMPTY_RESPONSE"));
                }
                @SuppressWarnings("unchecked")
                T value = (T) body;
                return Single.just(value);
            }
            if (result instanceof ApiResult.HttpError<?> http) {
                return Single.error(new SafetyApiException(http.getStatus(), http.getErrorCode()));
            }
            return Single.error(((ApiResult.NetworkError<?>) result).getCause());
        });
    }

    private Completable emptyBody(@NonNull Call<?> call) {
        return ApiResult.safeApiCall(call).flatMapCompletable(result -> {
            if (result instanceof ApiResult.Success<?>) {
                return Completable.complete();
            }
            if (result instanceof ApiResult.HttpError<?> http) {
                return Completable.error(
                        new SafetyApiException(http.getStatus(), http.getErrorCode()));
            }
            return Completable.error(((ApiResult.NetworkError<?>) result).getCause());
        });
    }
}
