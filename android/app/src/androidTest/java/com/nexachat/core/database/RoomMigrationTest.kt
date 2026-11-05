package com.nexachat.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented proof that the Room migration chain is real.
 *
 * Two complementary checks:
 *  - [v7SchemaCreatesFromExportedJson] — the checked-in
 *    `schemas/…/7.json` must be usable by `MigrationTestHelper`, which is what
 *    makes every future migration testable;
 *  - [migrateV1ToV7PreservesRowsAndBackfillsColumns] and
 *    [migrateV6ToV7AddsEditDeleteAndInboxColumns] — a hand-written legacy
 *    database (the schema as it shipped) is opened through [AppDatabase] with
 *    the production migrations. Room validates the resulting tables against
 *    the entities on open, so a wrong `ALTER`/index name fails here, and the
 *    assertions pin the data the upgrade must preserve.
 *
 * The legacy DDL is intentionally literal: it is a snapshot of shipped
 * versions, not a second source of truth to keep in sync with the entities.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        context.deleteDatabase(LEGACY_DB)
        context.deleteDatabase(EXPORTED_DB)
    }

    @Test
    fun v7SchemaCreatesFromExportedJson() {
        helper.createDatabase(EXPORTED_DB, 7).close()
    }

    @Test
    fun migrateV1ToV7PreservesRowsAndBackfillsColumns() {
        createLegacyDatabase(version = 1, ddl = V1_SCHEMA) { db ->
            db.execSQL(
                "INSERT INTO conversations (id, type, title, lastMessageSeq, " +
                    "lastMessageContent, lastMessageSenderName, lastMessageAt, " +
                    "unreadCount, otherUserName, otherUserAvatar) " +
                    "VALUES ('c-1', 'GROUP', 'Team', 7, 'hi', 'Lan', 100, 3, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO messages (id, conversationId, seq, senderId, " +
                    "senderDisplayName, type, content, clientMessageId, replyToId, " +
                    "createdAt, attachmentsJson) " +
                    "VALUES ('m-1', 'c-1', 7, 'u-1', 'Lan', 'TEXT', 'hi', NULL, NULL, 100, NULL)",
            )
            db.execSQL(
                "INSERT INTO outbox (clientMessageId, conversationId, type, content, " +
                    "replyToId, state, createdAt) " +
                    "VALUES ('cm-1', 'c-1', 'TEXT', 'queued', NULL, 'SENDING', 100)",
            )
        }

        openMigrated().use(::assertV7ShapeAndPreservedRows)
    }

    @Test
    fun migrateV6ToV7AddsEditDeleteAndInboxColumns() {
        createLegacyDatabase(version = 6, ddl = V6_SCHEMA) { db ->
            db.execSQL(
                "INSERT INTO conversations (id, type, title, lastMessageSeq, " +
                    "lastMessageContent, lastMessageSenderName, lastMessageAt, " +
                    "unreadCount, otherUserName, otherUserAvatar, peerReadSeq, avatarUrl, myRole) " +
                    "VALUES ('c-1', 'DIRECT', NULL, 7, 'hi', 'Lan', 100, 3, 'Lan', NULL, 4, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO messages (id, conversationId, seq, senderId, " +
                    "senderDisplayName, type, content, clientMessageId, replyToId, " +
                    "replyToSenderName, replyToSnippet, createdAt, attachmentsJson) " +
                    "VALUES ('m-1', 'c-1', 7, 'u-1', 'Lan', 'TEXT', 'hi', NULL, NULL, " +
                    "NULL, NULL, 100, NULL)",
            )
        }

        openMigrated().use { db ->
            db.query(
                "SELECT editedAtEpochMs, deletedAtEpochMs FROM messages",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
            db.query(
                "SELECT otherUserId, lastMessageDeleted, peerReadSeq FROM conversations",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(4L, cursor.getLong(2))
            }
        }
    }

    @Test
    fun migrateV5ToV6AddsNullableRoleWithoutTouchingTheRest() {
        createLegacyDatabase(version = 5, ddl = V5_SCHEMA) { db ->
            db.execSQL(
                "INSERT INTO conversations (id, type, title, lastMessageSeq, " +
                    "lastMessageContent, lastMessageSenderName, lastMessageAt, " +
                    "unreadCount, otherUserName, otherUserAvatar, peerReadSeq, avatarUrl) " +
                    "VALUES ('c-1', 'GROUP', 'Team', 7, 'hi', 'Lan', 100, 3, NULL, NULL, 4, 'avatar-1')",
            )
        }

        openMigrated().use { db ->
            db.query("SELECT myRole, peerReadSeq, avatarUrl FROM conversations").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertEquals(4L, cursor.getLong(1))
                assertEquals("avatar-1", cursor.getString(2))
            }
        }
    }

    private fun assertV7ShapeAndPreservedRows(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT title, unreadCount, peerReadSeq, avatarUrl, myRole, " +
                "otherUserId, lastMessageDeleted FROM conversations",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Team", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))
            assertEquals(0L, cursor.getLong(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertEquals(0, cursor.getInt(6))
        }
        db.query(
            "SELECT id, content, replyToSenderName, replyToSnippet, " +
                "editedAtEpochMs, deletedAtEpochMs FROM messages",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("m-1", cursor.getString(0))
            assertEquals("hi", cursor.getString(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
        }
        db.query("SELECT content, attachmentsJson FROM outbox").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("queued", cursor.getString(0))
            assertEquals("", cursor.getString(1))
        }
        // The interaction tables exist with the shape every writer expects.
        db.execSQL(
            "INSERT INTO pinned_messages (conversationId, messageId, pinnedAt) " +
                "VALUES ('c-1', 'm-1', 1)",
        )
        db.execSQL(
            "INSERT INTO message_reactions (messageId, conversationId, emoji, count, reactedByMe) " +
                "VALUES ('m-1', 'c-1', 'x', 1, 0)",
        )
    }

    /**
     * Opens the legacy file through Room with the production migrations. Room
     * validates every table/index against the entities inside `onOpen`, so
     * this call is itself the migration assertion.
     */
    private fun openMigrated(): SupportSQLiteDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, LEGACY_DB)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .allowMainThreadQueries()
            .build()
            .openHelper
            .writableDatabase

    private fun createLegacyDatabase(
        version: Int,
        ddl: List<String>,
        seed: (SQLiteDatabase) -> Unit,
    ) {
        context.deleteDatabase(LEGACY_DB)
        val file = context.getDatabasePath(LEGACY_DB)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            ddl.forEach(db::execSQL)
            seed(db)
            db.version = version
        } finally {
            db.close()
        }
    }

    private companion object {
        const val LEGACY_DB = "nexachat-migration-legacy.db"
        const val EXPORTED_DB = "nexachat-migration-exported.db"

        /** Schema as shipped in version 1 (before any migration). */
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `messages` (" +
                "`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                "`seq` INTEGER NOT NULL, `senderId` TEXT NOT NULL, " +
                "`senderDisplayName` TEXT, `type` TEXT NOT NULL, `content` TEXT, " +
                "`clientMessageId` TEXT, `replyToId` TEXT, `createdAt` INTEGER NOT NULL, " +
                "`attachmentsJson` TEXT, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_seq` " +
                "ON `messages` (`conversationId`, `seq`)",
            "CREATE TABLE IF NOT EXISTS `conversations` (" +
                "`id` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT, " +
                "`lastMessageSeq` INTEGER NOT NULL, `lastMessageContent` TEXT, " +
                "`lastMessageSenderName` TEXT, `lastMessageAt` INTEGER, " +
                "`unreadCount` INTEGER NOT NULL, `otherUserName` TEXT, " +
                "`otherUserAvatar` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `outbox` (" +
                "`clientMessageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, `content` TEXT NOT NULL, `replyToId` TEXT, " +
                "`state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`clientMessageId`))",
        )

        /** Schema as shipped in version 5 (everything before `myRole`). */
        val V5_SCHEMA = V1_SCHEMA + listOf(
            // v2: pending attachment ids must survive a process restart.
            "ALTER TABLE outbox ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE messages ADD COLUMN replyToSenderName TEXT",
            "ALTER TABLE messages ADD COLUMN replyToSnippet TEXT",
            "CREATE TABLE IF NOT EXISTS `message_reactions` (" +
                "`messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                "`emoji` TEXT NOT NULL, `count` INTEGER NOT NULL, " +
                "`reactedByMe` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `emoji`))",
            "CREATE INDEX IF NOT EXISTS `index_message_reactions_conversationId` " +
                "ON `message_reactions` (`conversationId`)",
            "CREATE TABLE IF NOT EXISTS `pinned_messages` (" +
                "`conversationId` TEXT NOT NULL, `messageId` TEXT NOT NULL, " +
                "`pinnedAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`, `messageId`))",
            "ALTER TABLE conversations ADD COLUMN peerReadSeq INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE conversations ADD COLUMN avatarUrl TEXT",
        )

        /** Schema as shipped in version 6 (before edit/delete + presence keys). */
        val V6_SCHEMA = V5_SCHEMA + listOf(
            "ALTER TABLE conversations ADD COLUMN myRole TEXT",
        )
    }
}
