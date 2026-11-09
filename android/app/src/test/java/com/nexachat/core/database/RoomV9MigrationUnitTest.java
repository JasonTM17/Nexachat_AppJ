package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class RoomV9MigrationUnitTest {

    @Test
    public void migration8To9DdlMatchesExportedSchema() throws IOException {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_8_9.migrate(db);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(db, org.mockito.Mockito.times(2)).execSQL(sqlCaptor.capture());

        List<String> executed = sqlCaptor.getAllValues();
        assertEquals(2, executed.size());

        String addedPinned = findStatement(executed, "ALTER TABLE `conversations` ADD COLUMN `pinned`");
        assertEquals(
                "ALTER TABLE `conversations` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0",
                normalizeSql(addedPinned));

        String addedPinnedAt = findStatement(executed, "ALTER TABLE `conversations` ADD COLUMN `pinnedAt`");
        assertEquals(
                "ALTER TABLE `conversations` ADD COLUMN `pinnedAt` INTEGER",
                normalizeSql(addedPinnedAt));

        String schemaJson = readExportedSchema("9.json");
        assertTrue(
                "Exported v9 schema must declare pinned as a required integer",
                Pattern.compile(
                                "\\\"columnName\\\"\\s*:\\s*\\\"pinned\\\""
                                        + "\\s*,\\s*\\\"affinity\\\"\\s*:\\s*\\\"INTEGER\\\""
                                        + "\\s*,\\s*\\\"notNull\\\"\\s*:\\s*true")
                        .matcher(schemaJson)
                        .find());

        assertTrue(
                "Exported v9 schema must declare pinnedAt as an optional integer",
                Pattern.compile(
                                "\\\"columnName\\\"\\s*:\\s*\\\"pinnedAt\\\""
                                        + "\\s*,\\s*\\\"affinity\\\"\\s*:\\s*\\\"INTEGER\\\""
                                        + "\\s*,\\s*\\\"notNull\\\"\\s*:\\s*false")
                        .matcher(schemaJson)
                        .find());
    }

    @Test
    public void conversationEntityPinnedContract() {
        ConversationEntity conv1 = new ConversationEntity(
                "c-1", "DIRECT", "Chat", 10L, "Hello", "Lan",
                1000L, 0, "Lan", null, null, 8L, null, "u-peer", false, 9L,
                true, 1700000000000L);
        ConversationEntity conv2 = new ConversationEntity(
                "c-1", "DIRECT", "Chat", 10L, "Hello", "Lan",
                1000L, 0, "Lan", null, null, 8L, null, "u-peer", false, 9L,
                true, 1700000000000L);
        ConversationEntity convDiff = new ConversationEntity(
                "c-1", "DIRECT", "Chat", 10L, "Hello", "Lan",
                1000L, 0, "Lan", null, null, 8L, null, "u-peer", false, 9L,
                false, null);

        assertTrue(conv1.isPinned());
        assertEquals(Long.valueOf(1700000000000L), conv1.getPinnedAt());
        assertEquals(conv1, conv2);
        assertEquals(conv1.hashCode(), conv2.hashCode());
        assertNotEquals(conv1, convDiff);
        assertNotNull(conv1.toString());

        ConversationEntity unpinned = conv1.withPinned(false, null);
        assertFalse(unpinned.isPinned());
        assertNull(unpinned.getPinnedAt());
        assertEquals(convDiff, unpinned);
    }

    @Test
    public void inboxComparatorOrderingWithTies() {
        // Pinned conversations should always be sorted before unpinned ones.
        // Among pinned: highest pinnedAt first.
        // Ties in pinnedAt: highest lastMessageAt first.
        // Ties in lastMessageAt: lowest id first (alphabetical).
        // Among unpinned: highest lastMessageAt first, tie-break by id.

        ConversationSummary pinHighest = createSummary("c-p3", true, 3000L, 500L);
        ConversationSummary pinTieHighMsg = createSummary("c-p2", true, 2000L, 1500L);
        ConversationSummary pinTieLowId = createSummary("c-p0", true, 2000L, 1000L);
        ConversationSummary pinTieHighId = createSummary("c-p1", true, 2000L, 1000L);

        ConversationSummary unpinRecent = createSummary("u-2", false, null, 6000L);
        ConversationSummary unpinOlder = createSummary("u-1", false, null, 5000L);

        List<ConversationSummary> list = new ArrayList<>(Arrays.asList(
                unpinOlder, pinTieLowId, unpinRecent, pinHighest, pinTieHighId, pinTieHighMsg));

        list.sort(ConversationSummary.INBOX_COMPARATOR);

        List<String> expectedOrder = Arrays.asList("c-p3", "c-p2", "c-p0", "c-p1", "u-2", "u-1");
        List<String> actualOrder = list.stream().map(ConversationSummary::getId).toList();

        assertEquals(expectedOrder, actualOrder);
    }

    private static ConversationSummary createSummary(
            String id, boolean pinned, Long pinnedAtEpochMs, Long lastMessageAtEpochMs) {
        return new ConversationSummary(
                id,
                "DIRECT",
                "Title " + id,
                "Preview",
                "Sender",
                lastMessageAtEpochMs,
                1L,
                0,
                null,
                null,
                null,
                0L,
                0L,
                null,
                null,
                false,
                null,
                pinned,
                pinnedAtEpochMs);
    }

    private static String findStatement(List<String> statements, String prefix) {
        return statements.stream()
                .filter(statement -> normalizeSql(statement).startsWith(normalizeSql(prefix)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing migration SQL: " + prefix));
    }

    private static String readExportedSchema(String filename) throws IOException {
        String resource =
                "schemas/com.nexachat.core.database.AppDatabase/" + filename;
        try (java.io.InputStream input =
                RoomV9MigrationUnitTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input != null) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        Path modulePath = Path.of(
                System.getProperty("user.dir"), "schemas", "com.nexachat.core.database.AppDatabase", filename);
        Path repoRootPath = Path.of(
                System.getProperty("user.dir"), "android", "app", "schemas",
                "com.nexachat.core.database.AppDatabase", filename);
        Path schemaPath = Files.isRegularFile(modulePath) ? modulePath : repoRootPath;
        assertTrue("Exported Room schema not found at " + schemaPath, Files.isRegularFile(schemaPath));
        return new String(Files.readAllBytes(schemaPath), StandardCharsets.UTF_8);
    }

    private static String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
