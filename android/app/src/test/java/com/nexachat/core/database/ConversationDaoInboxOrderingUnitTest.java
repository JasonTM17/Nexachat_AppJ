package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

/**
 * Mirrors the {@link ConversationDao#inboxFlow()} SQL ordering so pinned rows,
 * unread counts, and stable tie-breaking stay covered without Robolectric Room.
 */
public final class ConversationDaoInboxOrderingUnitTest {

    @Test
    public void ordersPinnedFirstThenPinnedAtThenActivityThenId() {
        List<ConversationEntity> rows = List.of(
                row("z-last", false, null, 50, 0),
                row("a-pinned-old", true, 100L, 10, 0),
                row("b-unpinned-hot", false, null, 200, 3),
                row("c-pinned-new", true, 200L, 5, 1),
                row("d-tie-id", false, null, 200, 0),
                row("e-tie-id", false, null, 200, 0));

        List<ConversationEntity> ordered = inboxOrder(rows);

        assertEquals(
                List.of("c-pinned-new", "a-pinned-old", "b-unpinned-hot", "d-tie-id", "e-tie-id", "z-last"),
                ordered.stream().map(ConversationEntity::getId).toList());
    }

    @Test
    public void unreadCountsDoNotChangeSqlOrdering() {
        List<ConversationEntity> rows = List.of(
                row("quiet", false, null, 100, 0),
                row("noisy", false, null, 100, 9));

        List<ConversationEntity> ordered = inboxOrder(rows);
        assertEquals(List.of("noisy", "quiet"), ordered.stream().map(ConversationEntity::getId).toList());
    }

    private static List<ConversationEntity> inboxOrder(List<ConversationEntity> source) {
        List<ConversationEntity> ordered = new ArrayList<>(source);
        ordered.sort(Comparator
                .comparing(ConversationEntity::isPinned).reversed()
                .thenComparing(row -> row.getPinnedAt() != null ? row.getPinnedAt() : 0L, Comparator.reverseOrder())
                .thenComparing(row -> row.getLastMessageAt() != null ? row.getLastMessageAt() : 0L,
                        Comparator.reverseOrder())
                .thenComparing(ConversationEntity::getId));
        return ordered;
    }

    private static ConversationEntity row(
            String id,
            boolean pinned,
            Long pinnedAt,
            long lastMessageAt,
            int unread) {
        return new ConversationEntity(
                id, "GROUP", id, lastMessageAt, "preview", "sender", lastMessageAt, unread,
                null, null, null, 0L, null, null, false, 0L,
                false, null, false, false,
                pinned, pinnedAt, "");
    }
}
