package com.nexachat.core.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

/** Synchronous methods are called on the sync IO lane, inside the page transaction for writes. */
@Dao
public interface SyncCursorDao {
    @Query("SELECT * FROM sync_cursors WHERE ownerUserId = :owner AND conversationId = :conversation LIMIT 1")
    SyncCursorEntity find(String owner, String conversation);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void put(SyncCursorEntity cursor);

    @Query("DELETE FROM sync_cursors WHERE ownerUserId = :owner AND conversationId = :conversation")
    void delete(String owner, String conversation);

    @Query("DELETE FROM sync_cursors WHERE ownerUserId = :owner")
    void deleteForOwner(String owner);
}
