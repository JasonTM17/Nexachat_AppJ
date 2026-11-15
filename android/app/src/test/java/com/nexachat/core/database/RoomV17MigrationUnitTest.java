package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Room 17 adds the per-account keyset trust table; all columns are additive. */
public final class RoomV17MigrationUnitTest {

    @Test public void migrationCreatesTrustTable() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_16_17.migrate(db);
        assertEquals(16, AppDatabase.MIGRATION_16_17.startVersion);
        assertEquals(17, AppDatabase.MIGRATION_16_17.endVersion);
        verify(db).execSQL("CREATE TABLE IF NOT EXISTS `e2ee_keyset_trust` ("
                + "`owner_user_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, "
                + "`state` TEXT NOT NULL, `verified_digest_hex` TEXT NOT NULL, "
                + "`verified_transcript` BLOB NOT NULL, `changed_digest_hex` TEXT, "
                + "`updated_at_ms` INTEGER NOT NULL, "
                + "PRIMARY KEY(`owner_user_id`, `conversation_id`))");
    }

    @Test public void exportedSchemaContainsTrustTable() throws Exception {
        Path schema = Path.of("schemas/com.nexachat.core.database.AppDatabase/17.json");
        if (!Files.exists(schema)) schema = Path.of("android/app").resolve(schema);
        String json = new String(Files.readAllBytes(schema), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("missing e2ee_keyset_trust table", json.contains("e2ee_keyset_trust"));
        assertTrue("missing owner key", json.contains("owner_user_id"));
        assertTrue("missing pinned transcript", json.contains("verified_transcript"));
        assertTrue("missing observed digest", json.contains("changed_digest_hex"));
        assertTrue("missing timestamp", json.contains("updated_at_ms"));
    }
}
