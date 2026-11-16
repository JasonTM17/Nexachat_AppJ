package com.nexachat.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.core.database.MessageTranslationDao;
import com.nexachat.core.database.MessageTranslationEntity;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.TranslateApi;
import com.nexachat.core.network.dto.TranslateBatchRequest;
import com.nexachat.core.network.dto.TranslateBatchResponse;
import com.nexachat.core.network.dto.TranslatePrefsDto;
import com.nexachat.core.network.dto.TranslateRequest;
import com.nexachat.core.network.dto.TranslateResponseDto;
import com.nexachat.core.network.dto.TranslationGlossaryDto;
import com.nexachat.core.network.dto.UpdateTranslatePrefsRequest;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import retrofit2.Call;

/**
 * Server translation calls plus the local Room cache. Cache entries are keyed
 * by the message's stored {@code changeVersion} (read from the messages
 * table), so an edit or recall makes prior rows unreachable without a sweep.
 */
@Singleton
public final class TranslationRepository {

    private final TranslateApi api;
    private final MessageTranslationDao translationDao;
    private final MessageDao messageDao;

    @Inject
    public TranslationRepository(
            @NonNull TranslateApi api,
            @NonNull MessageTranslationDao translationDao,
            @NonNull MessageDao messageDao) {
        this.api = api;
        this.translationDao = translationDao;
        this.messageDao = messageDao;
    }

    /**
     * Cache-first single-message translation. The cache key uses the Room
     * row's current {@code changeVersion}, so an edited message is always a
     * miss and a recalled one never reaches the network (the ViewModel gates
     * on the visible tombstone too).
     */
    @NonNull
    public Single<Result<TranslateResponseDto>> translate(
            @NonNull String messageId, @NonNull String targetLang) {
        String normalized = normalize(targetLang);
        return messageDao.byId(messageId)
                .flatMapSingle(entity -> fetchFor(entity, normalized))
                .switchIfEmpty(Single.just(Result.failure(
                        new ApiError.Unknown("MESSAGE_NOT_FOUND"))))
                .subscribeOn(Schedulers.io());
    }

    private Single<Result<TranslateResponseDto>> fetchFor(
            @NonNull MessageEntity entity, String targetLang) {
        long version = entity.getChangeVersion();
        return translationDao.find(entity.getId(), version, targetLang)
                .flatMapSingle(row -> Single.<Result<TranslateResponseDto>>just(
                        new Result.Success<>(new TranslateResponseDto(
                                row.getTranslatedText(), row.getSourceLang(),
                                row.getTargetLang(), true))))
                .switchIfEmpty(remoteTranslate(entity, version, targetLang));
    }

    private Single<Result<TranslateResponseDto>> remoteTranslate(
            @NonNull MessageEntity entity, long version, String targetLang) {
        return call(api.translate(entity.getId(), new TranslateRequest(targetLang)))
                .flatMap(result -> {
                    TranslateResponseDto dto = successOf(result);
                    if (dto == null || dto.getText() == null) {
                        return Single.just(result);
                    }
                    MessageTranslationEntity row = new MessageTranslationEntity(
                            entity.getId(), entity.getConversationId(), version, targetLang,
                            dto.getSourceLang() == null ? "" : dto.getSourceLang(),
                            dto.getText(), System.currentTimeMillis());
                    return translationDao.upsert(row).andThen(Single.just(result));
                });
    }

    /**
     * Batch translate for auto-translate. Rows are written with each message's
     * current stored version; unknown ids are skipped server-side.
     */
    @NonNull
    public Single<Result<TranslateBatchResponse>> translateBatch(
            @NonNull String conversationId,
            @NonNull List<String> messageIds,
            @NonNull String targetLang) {
        String normalized = normalize(targetLang);
        return messageDao.byIds(messageIds)
                .flatMap(entities -> {
                    Map<String, Long> versions = new HashMap<>();
                    for (MessageEntity message : entities) {
                        versions.put(message.getId(), message.getChangeVersion());
                    }
                    return call(api.translateBatch(conversationId,
                            new TranslateBatchRequest(messageIds, normalized)))
                            .flatMap(result -> persistBatch(result, conversationId, normalized,
                                    versions));
                })
                .subscribeOn(Schedulers.io());
    }

    private Single<Result<TranslateBatchResponse>> persistBatch(
            Result<TranslateBatchResponse> result, String conversationId,
            String normalized, Map<String, Long> versions) {
        TranslateBatchResponse body = successOf(result);
        List<MessageTranslationEntity> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (body != null && body.getResults() != null) {
            for (TranslateBatchResponse.Item item : body.getResults()) {
                if (item.getMessageId() == null || item.getText() == null) {
                    continue;
                }
                Long version = versions.get(item.getMessageId());
                rows.add(new MessageTranslationEntity(
                        item.getMessageId(), conversationId,
                        version == null ? 0L : version, normalized,
                        item.getSourceLang() == null ? "" : item.getSourceLang(),
                        item.getText(), now));
            }
        }
        if (rows.isEmpty()) {
            return Single.just(result);
        }
        return translationDao.upsertAll(rows).andThen(Single.just(result));
    }

    /** Reads cached rows for a set of messages (no network). */
    @NonNull
    public Single<List<MessageTranslationEntity>> cachedTranslations(
            @NonNull List<String> messageIds, @NonNull String targetLang) {
        if (messageIds.isEmpty()) {
            return Single.just(List.of());
        }
        return translationDao.findAllFor(messageIds, normalize(targetLang))
                .subscribeOn(Schedulers.io());
    }

    /** Drops every cached row for a message (edit/recall hygiene). */
    public io.reactivex.rxjava3.core.Completable invalidate(@NonNull String messageId) {
        return translationDao.deleteForMessage(messageId).subscribeOn(Schedulers.io());
    }

    @NonNull
    public Single<Result<TranslationGlossaryDto>> getGlossary(@NonNull String conversationId) {
        return call(api.getGlossary(conversationId));
    }

    @NonNull
    public Single<Result<TranslationGlossaryDto>> putGlossary(
            @NonNull String conversationId, @NonNull List<TranslationGlossaryDto.Term> terms) {
        return call(api.putGlossary(conversationId, new TranslationGlossaryDto(terms)));
    }

    @NonNull
    public Single<Result<TranslatePrefsDto>> getPrefs(@NonNull String conversationId) {
        return call(api.getPrefs(conversationId));
    }

    @NonNull
    public Single<Result<TranslatePrefsDto>> setPrefs(
            @NonNull String conversationId, boolean autoTranslate, @Nullable String targetLang) {
        return call(api.updatePrefs(conversationId,
                new UpdateTranslatePrefsRequest(autoTranslate, targetLang)));
    }

    private static String normalize(String targetLang) {
        return targetLang.trim().toLowerCase(Locale.ROOT).replace('_', '-').split("-", 2)[0];
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static <T> T successOf(Result<T> result) {
        return result instanceof Result.Success<?> success ? (T) success.getValue() : null;
    }

    private static <T> Single<Result<T>> call(@NonNull Call<T> call) {
        return ApiResult.safeApiCall(call).map(TranslationRepository::toDomain)
                .subscribeOn(Schedulers.io());
    }

    private static <T> Result<T> toDomain(ApiResult<T> result) {
        if (result instanceof ApiResult.Success<?> success) {
            if (success.getBody() == null) {
                return Result.failure(new ApiError.Unknown("EMPTY_BODY"));
            }
            @SuppressWarnings("unchecked") T value = (T) success.getBody();
            return new Result.Success<>(value);
        }
        if (result instanceof ApiResult.HttpError<?> http) {
            return Result.failure(ApiError.from(http.getStatus(), http.getErrorCode()));
        }
        ApiResult.NetworkError<?> net = (ApiResult.NetworkError<?>) result;
        String message = net.getCause() == null ? null : net.getCause().getMessage();
        return Result.failure(new ApiError.Network(message));
    }
}
