package com.nexachat.core.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;

@Dao
public interface ComposerDraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable upsert(ComposerDraftEntity draft);

    @Query("DELETE FROM composer_drafts WHERE conversationId = :conversationId")
    Completable delete(String conversationId);

    @Query("SELECT * FROM composer_drafts WHERE conversationId = :conversationId")
    Maybe<ComposerDraftEntity> byId(String conversationId);

    @Query("SELECT * FROM composer_drafts")
    Flowable<List<ComposerDraftEntity>> observeAll();
}
