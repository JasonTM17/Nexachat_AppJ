package com.nexachat.core.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;

/** Local cache of server translations, keyed by message revision. */
@Dao
public interface MessageTranslationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable upsert(MessageTranslationEntity translation);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable upsertAll(List<MessageTranslationEntity> translations);

    @Query("SELECT * FROM message_translations "
            + "WHERE messageId = :messageId AND changeVersion = :changeVersion "
            + "AND targetLang = :targetLang LIMIT 1")
    Maybe<MessageTranslationEntity> find(String messageId, long changeVersion, String targetLang);

    /** Current-version rows only: pre-edit translations never match the join. */
    @Query("SELECT mt.* FROM message_translations mt "
            + "INNER JOIN messages m ON m.id = mt.messageId "
            + "AND m.changeVersion = mt.changeVersion "
            + "WHERE mt.messageId IN (:messageIds) AND mt.targetLang = :targetLang")
    Single<List<MessageTranslationEntity>> findAllFor(List<String> messageIds, String targetLang);

    @Query("DELETE FROM message_translations WHERE messageId = :messageId")
    Completable deleteForMessage(String messageId);

    @Query("DELETE FROM message_translations WHERE conversationId = :conversationId")
    Completable deleteForConversation(String conversationId);

    /** TTL eviction: rows fetched before {@code cutoffMillis} are stale. */
    @Query("DELETE FROM message_translations WHERE fetchedAt < :cutoffMillis")
    Completable deleteOlderThan(long cutoffMillis);
}
