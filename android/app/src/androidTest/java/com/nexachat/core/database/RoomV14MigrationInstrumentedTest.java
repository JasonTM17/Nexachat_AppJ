package com.nexachat.core.database;

import static org.junit.Assert.*;

import android.database.Cursor;
import androidx.room.testing.MigrationTestHelper;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import org.junit.After;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Phase08: run on a device. No new library, no emulator is needed to compile this test. */
@RunWith(AndroidJUnit4.class)
public final class RoomV14MigrationInstrumentedTest {
    @Rule public final MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(), AppDatabase.class);

    @After public void cleanup() {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("room14-upgrade.db");
        context.deleteDatabase("room14-fault.db");
    }

    @Test public void upgradePreservesHistoryAndBothOutboxesAndResetsLostCache() throws Exception {
        String name = "room14-upgrade.db";
        SupportSQLiteDatabase old = helper.createDatabase(name, 13);
        old.execSQL("INSERT INTO messages(id,conversationId,seq,senderId,type,content,createdAt) "
                + "VALUES('m','c',9,'A','TEXT','history',1)");
        old.execSQL("INSERT INTO outbox(clientMessageId,conversationId,type,content,state,createdAt,"
                + "attachmentsJson,ownerUserId,ownerResolved) VALUES('queued','c','TEXT','send','SENDING',2,'', 'A',1)");
        old.execSQL("INSERT INTO offline_outbox(id,idempotencyKey,actionType,payloadJson,state,retryCount,"
                + "createdAtEpochMs,lastAttemptEpochMs) VALUES('action','key','SEND_MESSAGE','{}','PENDING',0,2,0)");
        old.close();
        SupportSQLiteDatabase upgraded = helper.runMigrationsAndValidate(name, 14, true, AppDatabase.MIGRATION_13_14);
        try {
            assertEquals("history", string(upgraded, "SELECT content FROM messages WHERE id='m'"));
            assertEquals("send", string(upgraded, "SELECT content FROM outbox WHERE clientMessageId='queued'"));
            assertEquals("A", string(upgraded, "SELECT ownerUserId FROM outbox WHERE clientMessageId='queued'"));
            assertEquals(1, number(upgraded, "SELECT COUNT(*) FROM offline_outbox"));
            assertEquals(0, number(upgraded, "SELECT changeVersion FROM messages WHERE id='m'"));
            assertEquals(0, number(upgraded, "SELECT generation FROM messages WHERE id='m'"));
            upgraded.execSQL("INSERT INTO sync_cursors VALUES('A','c',1,9,'2026-11-12T00:00:00Z',7,NULL,0)");
            upgraded.execSQL("INSERT OR REPLACE INTO sync_cursors VALUES('A','c',2,0,NULL,NULL,NULL,1)");
            assertEquals("only one active generation", 1, number(upgraded, "SELECT COUNT(*) FROM sync_cursors"));
            upgraded.execSQL("DELETE FROM messages WHERE id='m'");
            assertEquals("partial cache loss resets its cursor", 0, number(upgraded, "SELECT COUNT(*) FROM sync_cursors"));
            assertEquals("queued send survives cache loss", 1, number(upgraded, "SELECT COUNT(*) FROM outbox"));
        } finally { upgraded.close(); }
    }

    @Test public void transactionFaultBeforeCommitReplaysAndAfterCommitResumes() throws Exception {
        SupportSQLiteDatabase db = helper.createDatabase("room14-fault.db", 14);
        try {
            db.beginTransaction();
            try { page(db); /* fault before setTransactionSuccessful: Room rolls back */ }
            finally { db.endTransaction(); }
            assertEquals(0, number(db, "SELECT COUNT(*) FROM messages"));
            assertEquals(0, number(db, "SELECT COUNT(*) FROM sync_cursors"));
            db.beginTransaction();
            try { page(db); db.setTransactionSuccessful(); }
            finally { db.endTransaction(); }
        } finally { db.close(); }
        AppDatabase reopened = Room.databaseBuilder(ApplicationProvider.getApplicationContext(),
                AppDatabase.class, "room14-fault.db").addCallback(AppDatabase.SYNC_CACHE_CALLBACK).build();
        try {
            assertNotNull("committed row survives reopen", reopened.messageDao().byId("m").blockingGet());
            assertEquals("committed cursor survives reopen", 1,
                    reopened.syncCursorDao().find("A", "c").changeCursor);
        } finally { reopened.close(); }
    }

    private static void page(SupportSQLiteDatabase db) {
        db.execSQL("INSERT INTO messages(id,conversationId,seq,senderId,type,createdAt,changeVersion,generation) "
                + "VALUES('m','c',1,'A','TEXT',1,1,1)");
        db.execSQL("INSERT INTO sync_cursors VALUES('A','c',1,1,NULL,NULL,NULL,1)");
    }
    private static long number(SupportSQLiteDatabase db, String query) {
        try (Cursor cursor = db.query(query)) { assertTrue(cursor.moveToFirst()); return cursor.getLong(0); }
    }
    private static String string(SupportSQLiteDatabase db, String query) {
        try (Cursor cursor = db.query(query)) { assertTrue(cursor.moveToFirst()); return cursor.getString(0); }
    }
}
