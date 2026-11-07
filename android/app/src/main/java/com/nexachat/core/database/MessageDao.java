package com.nexachat.core.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;

/**
 * Message history access. Inserts ignore duplicate ids so redelivered events
 * and overlapping history pages cannot create a second row.
 */
@Dao
public interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    Completable upsert(MessageEntity message);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    Completable upsertAll(List<MessageEntity> messages);

    /** Full-row replace for an optimistic edit/delete and its restore path. */
    @Update
    Completable update(MessageEntity message);

    @Query("SELECT * FROM messages WHERE id = :messageId")
    Maybe<MessageEntity> byId(String messageId);

    /** Clears embedded previews for replies that reference a tombstoned message. */
    @Query("UPDATE messages SET replyToSnippet = NULL WHERE replyToId = :messageId")
    Completable clearReplySnippets(String messageId);

    /** Chronological stream for one conversation; UI sorts by seq, not time. */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY seq ASC")
    Flowable<List<MessageEntity>> messagesForConversation(String conversationId);

    /** Highest stored seq, the resync cursor. */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE conversationId = :conversationId")
    Single<Long> maxSeq(String conversationId);

    /** Lowest stored seq, the page-back cursor. */
    @Query("SELECT COALESCE(MIN(seq), 0) FROM messages WHERE conversationId = :conversationId")
    Single<Long> minSeq(String conversationId);

    /** Whether one message is already stored. */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :messageId)")
    Single<Boolean> exists(String messageId);

    @Query("DELETE FROM messages")
    Completable deleteAll();
}
