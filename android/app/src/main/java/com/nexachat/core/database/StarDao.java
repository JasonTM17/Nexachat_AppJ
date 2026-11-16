package com.nexachat.core.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;

/** Local star markers; inserts replace so the newest star timestamp wins. */
@Dao
public interface StarDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable upsert(StarredMessageEntity star);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable upsertAll(List<StarredMessageEntity> stars);

    @Query("DELETE FROM message_stars WHERE messageId = :messageId")
    Completable delete(String messageId);

    /** Live star id set for one conversation's bubbles and action sheet. */
    @Query("SELECT messageId FROM message_stars WHERE conversationId = :conversationId")
    Flowable<List<String>> starredIdsFor(String conversationId);

    @Query("SELECT * FROM message_stars ORDER BY starredAt DESC")
    Flowable<List<StarredMessageEntity>> all();

    @Query("DELETE FROM message_stars")
    Completable deleteAll();
}
