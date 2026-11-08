package com.nexachat.core.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;

/**
 * Access to persisted call logs.
 */
@Dao
public interface CallLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable insert(CallLogEntity callLog);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable insertAll(List<CallLogEntity> callLogs);

    @Query("SELECT * FROM call_logs ORDER BY startedAt DESC")
    Flowable<List<CallLogEntity>> getAllCallLogs();

    @Query("SELECT * FROM call_logs ORDER BY startedAt DESC")
    Single<List<CallLogEntity>> getAllCallLogsList();

    @Query("SELECT * FROM call_logs WHERE conversationId = :conversationId ORDER BY startedAt DESC")
    Flowable<List<CallLogEntity>> getCallLogsForConversation(String conversationId);

    @Query("SELECT * FROM call_logs WHERE callId = :callId")
    Maybe<CallLogEntity> findById(String callId);

    @Query("DELETE FROM call_logs WHERE callId = :callId")
    Completable deleteByCallId(String callId);

    @Query("DELETE FROM call_logs")
    Completable clear();
}
