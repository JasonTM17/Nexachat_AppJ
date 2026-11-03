package com.nexachat.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Message history access. Inserts are `IGNORE` — receivers dedup by message
 * id (plan §11.3.3: a redelivered MESSAGE_CREATED or an overlapping history
 * page can never produce a second row).
 */
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    /** Chronological stream for one conversation — UI sorts by `seq`, not time. */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY seq ASC")
    fun messagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    /** Highest stored seq — the resync cursor (`afterSeq` equivalent). */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE conversationId = :conversationId")
    suspend fun maxSeq(conversationId: String): Long

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(conversations: List<ConversationEntity>)

    /** Inbox ordering — most recent activity first, empty conversations last. */
    @Query("SELECT * FROM conversations ORDER BY COALESCE(lastMessageAt, 0) DESC")
    fun inboxFlow(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun observeById(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun byId(conversationId: String): ConversationEntity?

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :conversationId")
    suspend fun clearUnread(conversationId: String)

    /**
     * Denormalized last-message projection for one incoming WS event. The
     * `seq > lastMessageSeq` guard keeps out-of-order redeliveries from
     * rewinding the inbox row (plan §11.3.4 ordering).
     */
    @Query(
        "UPDATE conversations SET lastMessageSeq = :seq, lastMessageContent = :content, " +
            "lastMessageSenderName = :senderName, lastMessageAt = :at " +
            "WHERE id = :conversationId AND :seq > lastMessageSeq",
    )
    suspend fun updateLastMessage(
        conversationId: String,
        seq: Long,
        content: String?,
        senderName: String?,
        at: Long,
    )

    @Query("UPDATE conversations SET unreadCount = unreadCount + :delta WHERE id = :conversationId")
    suspend fun incrementUnread(conversationId: String, delta: Int)

    @Query("DELETE FROM conversations")
    suspend fun clear()
}

/**
 * Outbox access. [pendingOrdered] feeds the UI merge (both SENDING and FAILED
 * rows render); [pendingList] feeds the WS flush — only SENDING rows retry.
 */
@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: OutboxEntity)

    @Query("UPDATE outbox SET state = :state WHERE clientMessageId = :clientMessageId")
    suspend fun updateState(clientMessageId: String, state: String)

    /** Whole queue oldest-first — the UI filters by conversation. */
    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    fun pendingOrdered(): Flow<List<OutboxEntity>>

    /** Flush order — strictly SENDING rows, oldest first (plan: ordered flush). */
    @Query("SELECT * FROM outbox WHERE state = 'SENDING' ORDER BY createdAt ASC")
    suspend fun pendingList(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE clientMessageId = :clientMessageId")
    suspend fun byId(clientMessageId: String): OutboxEntity?

    @Query("DELETE FROM outbox WHERE clientMessageId = :clientMessageId")
    suspend fun delete(clientMessageId: String)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}
