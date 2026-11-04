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
 *
 * v3: phase-10 message interactions — `message_reactions` (aggregated
 * per-emoji summaries), `pinned_messages` (conversation pins), and the
 * denormalized `replyTo` preview columns on `messages`.
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        OutboxEntity::class,
        MessageReactionEntity::class,
        PinnedMessageEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun outboxDao(): OutboxDao
    abstract fun reactionDao(): ReactionDao
    abstract fun pinDao(): PinDao

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

        /**
         * All additive: two nullable preview columns on `messages` plus the
         * two new interaction tables. Index names must match Room's
         * `index_<table>_<columns>` convention or schema validation fails.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToSenderName TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToSnippet TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `message_reactions` (
                        `messageId` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `emoji` TEXT NOT NULL,
                        `count` INTEGER NOT NULL,
                        `reactedByMe` INTEGER NOT NULL,
                        PRIMARY KEY(`messageId`, `emoji`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_reactions_conversationId` " +
                        "ON `message_reactions` (`conversationId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pinned_messages` (
                        `conversationId` TEXT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `pinnedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationId`, `messageId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Client-only peer read watermark on `conversations` — the column
         * never receives REST data, so existing installs default to 0 and
         * accumulate strictly from MESSAGE_READ events.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN peerReadSeq INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * GROUP avatar on `conversations` — REST-fed column, so a plain
         * nullable ALTER is safe; inbox refresh fills it.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN avatarUrl TEXT",
                )
            }
        }

        /**
         * `myRole` on `conversations` — inbox-fed member role used for
         * client affordance gating (GROUP pin is ADMIN-only). Nullable;
         * the next inbox refresh fills it.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN myRole TEXT",
                )
            }
        }
    }
}
