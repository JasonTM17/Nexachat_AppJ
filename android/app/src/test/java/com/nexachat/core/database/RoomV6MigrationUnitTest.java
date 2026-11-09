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

/** DDL contract for the v5 → v6 schema step (member role column on conversations). */
public final class RoomV6MigrationUnitTest {

    @Test
    public void migration5To6AddsMyRoleColumn() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_5_6.migrate(db);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(db, org.mockito.Mockito.times(1)).execSQL(sqlCaptor.capture());
        assertEquals(
                "ALTER TABLE conversations ADD COLUMN myRole TEXT",
                normalizeSql(sqlCaptor.getValue()));
    }

    @Test
    public void exportedV6SchemaDeclaresMyRoleOnConversations() throws IOException {
        String schemaJson = readExportedSchema("6.json");
        assertTrue(schemaJson.contains("\"tableName\": \"conversations\""));
        assertTrue(schemaJson.contains("\"columnName\": \"myRole\""));
    }

    private static String readExportedSchema(String filename) throws IOException {
        String resource = "schemas/com.nexachat.core.database.AppDatabase/" + filename;
        try (java.io.InputStream input = RoomV6MigrationUnitTest.class.getClassLoader()
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
