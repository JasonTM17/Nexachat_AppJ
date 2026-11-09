package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** DDL contract for edit/delete metadata and direct-peer inbox fields introduced at v7. */
public final class RoomV7MigrationUnitTest {

    @Test
    public void migration6To7AddsEditDeleteAndDirectPeerColumns() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_6_7.migrate(db);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(db, org.mockito.Mockito.times(4)).execSQL(sqlCaptor.capture());
        List<String> executed = sqlCaptor.getAllValues();

        assertEquals(
                "ALTER TABLE messages ADD COLUMN editedAtEpochMs INTEGER",
                normalizeSql(executed.get(0)));
        assertEquals(
                "ALTER TABLE messages ADD COLUMN deletedAtEpochMs INTEGER",
                normalizeSql(executed.get(1)));
        assertEquals(
                "ALTER TABLE conversations ADD COLUMN otherUserId TEXT",
                normalizeSql(executed.get(2)));
        assertEquals(
                "ALTER TABLE conversations ADD COLUMN lastMessageDeleted INTEGER NOT NULL DEFAULT 0",
                normalizeSql(executed.get(3)));
    }

    @Test
    public void exportedV7SchemaDeclaresTheNewMessageAndConversationColumns() throws IOException {
        String schemaJson = readExportedSchema("7.json");
        assertTrue(schemaJson.contains("\"columnName\": \"editedAtEpochMs\""));
        assertTrue(schemaJson.contains("\"columnName\": \"deletedAtEpochMs\""));
        assertTrue(schemaJson.contains("\"columnName\": \"otherUserId\""));
        assertTrue(schemaJson.contains("\"columnName\": \"lastMessageDeleted\""));
    }

    private static String readExportedSchema(String filename) throws IOException {
        String resource = "schemas/com.nexachat.core.database.AppDatabase/" + filename;
        try (java.io.InputStream input = RoomV7MigrationUnitTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input != null) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Path schemaPath = Path.of(System.getProperty("user.dir"), "schemas",
                "com.nexachat.core.database.AppDatabase", filename);
        if (!Files.isRegularFile(schemaPath)) {
            schemaPath = Path.of(System.getProperty("user.dir"), "android", "app", "schemas",
                    "com.nexachat.core.database.AppDatabase", filename);
        }
        assertTrue("Exported Room schema not found at " + schemaPath, Files.isRegularFile(schemaPath));
        return new String(Files.readAllBytes(schemaPath), StandardCharsets.UTF_8);
    }

    private static String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
