package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import androidx.sqlite.db.SupportSQLiteDatabase;
import com.nexachat.data.ConversationSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class RoomV10MigrationUnitTest {

    /** DDL smoke only; real row-preservation behavior is covered by the migration instrumented tests. */
    @Test
    public void migration9To10AddsThePinColumnsWithSafeDefaults() {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_9_10.migrate(db);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(db, org.mockito.Mockito.times(2)).execSQL(sqlCaptor.capture());
        List<String> executed = sqlCaptor.getAllValues();

        assertEquals(
                "ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0",
                normalizeSql(executed.get(0)));
        assertEquals(
                "ALTER TABLE conversations ADD COLUMN pinnedAt INTEGER",
                normalizeSql(executed.get(1)));
    }

    @Test
    public void exportedV10SchemaDeclaresThePinColumns() throws IOException {
        String schemaJson = readExportedSchema("10.json");

        assertTrue(schemaJson.contains("\"columnName\": \"pinned\""));
        assertTrue(schemaJson.contains("\"columnName\": \"pinnedAt\""));
        assertTrue("pinned must be a required integer",
                Pattern.compile("\\\"columnName\\\"\\s*:\\s*\\\"pinned\\\""
                                + "\\s*,\\s*\\\"affinity\\\"\\s*:\\s*\\\"INTEGER\\\""
                                + "\\s*,\\s*\\\"notNull\\\"\\s*:\\s*true")
                        .matcher(schemaJson).find());
        assertTrue("pinnedAt must be nullable",
                Pattern.compile("\\\"columnName\\\"\\s*:\\s*\\\"pinnedAt\\\""
                                + "\\s*,\\s*\\\"affinity\\\"\\s*:\\s*\\\"INTEGER\\\""
                                + "\\s*,\\s*\\\"notNull\\\"\\s*:\\s*false")
                        .matcher(schemaJson).find());
    }

    @Test
    public void conversationEntityPreservesPinnedFlagsAcrossCopies() {
        ConversationEntity row = new ConversationEntity(
                "c-1", "DIRECT", "Chat", 10L, "Hello", "Lan", 1_000L, 3,
                "Lan", null, null, 8L, null, "u-peer", false, 9L,
                true, 2_000L, true, true, true, 1_500L);

        assertTrue(row.isPinned());
        assertEquals(Long.valueOf(1_500L), row.getPinnedAt());
        assertTrue(row.isMuted());
        assertTrue(row.isArchived());
        assertTrue(row.isAiOptOut());

        ConversationEntity renamed = row.withTitle("Renamed");
        assertTrue(renamed.isPinned());
        assertEquals(Long.valueOf(1_500L), renamed.getPinnedAt());
        assertTrue(renamed.isMuted());
        assertTrue(renamed.isArchived());

        ConversationEntity withAvatar = row.withAvatarUrl("http://avatar.test");
        assertTrue(withAvatar.isPinned());
        assertEquals(Long.valueOf(1_500L), withAvatar.getPinnedAt());

        ConversationEntity withClient = row.withClientColumns(15L, "ADMIN", "u-other");
        assertTrue(withClient.isPinned());
        assertEquals(Long.valueOf(1_500L), withClient.getPinnedAt());

        ConversationEntity withDelivered = row.withPeerDeliveredSeq(12L);
        assertTrue(withDelivered.isPinned());
        assertEquals(Long.valueOf(1_500L), withDelivered.getPinnedAt());

        ConversationEntity unpinned = row.withPinned(false, null);
        assertFalse(unpinned.isPinned());
        assertNull(unpinned.getPinnedAt());
        assertTrue(unpinned.isMuted());
        assertTrue(unpinned.isArchived());
    }

    @Test
    public void inboxComparatorOrdersPinnedFirstAndTiesByActivity() {
        ConversationSummary pinnedOlder = summary("c-1", true, 100L, 500L, false);
        ConversationSummary pinnedNewer = summary("c-2", true, 200L, 300L, false);
        ConversationSummary unpinnedNewest = summary("c-3", false, null, 1000L, false);
        ConversationSummary unpinnedOlder = summary("c-4", false, null, 200L, false);

        List<ConversationSummary> list = new ArrayList<>(List.of(unpinnedOlder, unpinnedNewest, pinnedOlder, pinnedNewer));
        list.sort(ConversationSummary.INBOX_COMPARATOR);

        assertEquals(List.of("c-2", "c-1", "c-3", "c-4"),
                list.stream().map(ConversationSummary::getId).toList());
    }

    @Test
    public void inboxComparatorOrdersPinnedInsideArchivedVariant() {
        ConversationSummary pinnedArchived = summary("c-arch-pinned", true, 500L, 100L, true);
        ConversationSummary unpinnedArchivedRecent = summary("c-arch-recent", false, null, 900L, true);
        ConversationSummary unpinnedArchivedOlder = summary("c-arch-older", false, null, 200L, true);

        List<ConversationSummary> archivedList = new ArrayList<>(List.of(
                unpinnedArchivedOlder, pinnedArchived, unpinnedArchivedRecent));
        archivedList.sort(ConversationSummary.INBOX_COMPARATOR);

        assertEquals(List.of("c-arch-pinned", "c-arch-recent", "c-arch-older"),
                archivedList.stream().map(ConversationSummary::getId).toList());
    }

    private static ConversationSummary summary(
            String id, boolean pinned, Long pinnedAt, Long lastMessageAt, boolean archived) {
        return new ConversationSummary(
                id, "DIRECT", null, "Snippet", "Peer", lastMessageAt, 1L, 0,
                "Peer", null, null, 0L, 0L, null, null, false, null,
                false, null, archived, false, pinned, pinnedAt);
    }

    private static String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static String readExportedSchema(String filename) throws IOException {
        String resource = "schemas/com.nexachat.core.database.AppDatabase/" + filename;
        try (java.io.InputStream input =
                RoomV10MigrationUnitTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input != null) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        Path modulePath = Path.of(
                System.getProperty("user.dir"), "schemas",
                "com.nexachat.core.database.AppDatabase", filename);
        Path repoRootPath = Path.of(
                System.getProperty("user.dir"), "android", "app", "schemas",
                "com.nexachat.core.database.AppDatabase", filename);
        Path schemaPath = Files.isRegularFile(modulePath) ? modulePath : repoRootPath;
        assertTrue("Exported Room schema not found at " + schemaPath, Files.isRegularFile(schemaPath));
        return new String(Files.readAllBytes(schemaPath), StandardCharsets.UTF_8);
    }
}
