package com.nexachat.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * NexaChat local database — messages, inbox conversations, and the
 * optimistic-send outbox (plan §12.2 "Source of truth: Room").
 *
 * `exportSchema = false`: schema JSON export is a build-time convenience the
 * project does not consume — which also means Room `autoMigrations` are
 * impossible (they generate ALTERs from the exported schema diff). Schema
 * changes therefore use explicit [Migration] SQL, versioned like the
 * server's Flyway files.
 *
 * v2: `outbox.attachmentsJson` — pending attachment sends must survive a
 * process restart with their id list intact (phase-09 multimedia).
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        OutboxEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        /**
         * Additive `NOT NULL DEFAULT ''` ALTER — the Kotlin default matches the
         * SQL default, so rows predating v2 read back identical to new ones.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE outbox ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT ''",
                )
            }
        }
    }
}
