package com.nexachat.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * NexaChat local database v1 — messages, inbox conversations, and the
 * optimistic-send outbox (plan §12.2 "Source of truth: Room").
 *
 * `exportSchema = false`: schema JSON export is a build-time convenience the
 * project does not consume; migrations (when added) will be versioned SQL
 * like the server's Flyway files.
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        OutboxEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun outboxDao(): OutboxDao
}
