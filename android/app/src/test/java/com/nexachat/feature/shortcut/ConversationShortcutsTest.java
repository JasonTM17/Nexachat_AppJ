package com.nexachat.feature.shortcut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.nexachat.data.ConversationSummary;
import java.util.List;
import org.junit.Test;

public final class ConversationShortcutsTest {

    @Test
    public void archivedConversationsAreExcluded() {
        ConversationSummary visible = conv("visible", "DIRECT", "Binh", 100L, false, null, false);
        ConversationSummary archived = conv("archived", "DIRECT", "Chi", 200L, false, null, true);

        List<ConversationSummary> targets = ConversationShortcuts.selectTargets(
                List.of(archived, visible), 4);

        assertEquals(List.of(visible), targets);
    }

    @Test
    public void pinnedConversationsSortBeforeUnpinned() {
        ConversationSummary active = conv("active", "DIRECT", "Active", 500L, false, null, false);
        ConversationSummary pinned = conv("pinned", "DIRECT", "Pinned", 10L, true, 50L, false);

        List<ConversationSummary> targets = ConversationShortcuts.selectTargets(
                List.of(active, pinned), 4);

        assertEquals(List.of(pinned, active), targets);
    }

    @Test
    public void pinnedSortByPinnedAtDescending() {
        ConversationSummary older = conv("older", "DIRECT", "Older", 900L, true, 10L, false);
        ConversationSummary newer = conv("newer", "DIRECT", "Newer", 100L, true, 20L, false);

        List<ConversationSummary> targets = ConversationShortcuts.selectTargets(
                List.of(older, newer), 4);

        assertEquals(List.of(newer, older), targets);
    }

    @Test
    public void unpinnedSortByLastMessageDescendingWithNullsLast() {
        ConversationSummary noActivity = conv("none", "DIRECT", "None", null, false, null, false);
        ConversationSummary fresh = conv("fresh", "DIRECT", "Fresh", 300L, false, null, false);
        ConversationSummary stale = conv("stale", "DIRECT", "Stale", 100L, false, null, false);

        List<ConversationSummary> targets = ConversationShortcuts.selectTargets(
                List.of(noActivity, stale, fresh), 4);

        assertEquals(List.of(fresh, stale, noActivity), targets);
    }

    @Test
    public void nullPinnedAtSortsAfterDatedPins() {
        ConversationSummary undated = conv("undated", "GROUP", "Undated", 900L, true, null, false);
        ConversationSummary dated = conv("dated", "GROUP", "Dated", 100L, true, 30L, false);

        List<ConversationSummary> targets = ConversationShortcuts.selectTargets(
                List.of(undated, dated), 4);

        assertEquals(List.of(dated, undated), targets);
    }

    @Test
    public void selectionIsCappedAtMax() {
        List<ConversationSummary> targets = ConversationShortcuts.selectTargets(
                List.of(
                        conv("a", "DIRECT", "A", 400L, false, null, false),
                        conv("b", "DIRECT", "B", 300L, false, null, false),
                        conv("c", "DIRECT", "C", 200L, false, null, false)),
                2);

        assertEquals(2, targets.size());
        assertEquals("a", targets.get(0).getId());
        assertEquals("b", targets.get(1).getId());
    }

    @Test
    public void duplicateIdsAreDeduped() {
        ConversationSummary first = conv("dup", "DIRECT", "First", 300L, false, null, false);
        ConversationSummary second = conv("dup", "DIRECT", "Second", 100L, false, null, false);
        ConversationSummary other = conv("other", "DIRECT", "Other", 200L, false, null, false);

        List<ConversationSummary> targets = ConversationShortcuts.selectTargets(
                List.of(first, second, other), 4);

        assertEquals(2, targets.size());
        assertEquals("dup", targets.get(0).getId());
        assertEquals("First", targets.get(0).getDisplayName());
        assertEquals("other", targets.get(1).getId());
    }

    @Test
    public void nonPositiveMaxSelectsNothing() {
        assertTrue(ConversationShortcuts.selectTargets(
                List.of(conv("a", "DIRECT", "A", 100L, false, null, false)), 0).isEmpty());
    }

    private static ConversationSummary conv(
            String id, String type, String name, Long lastMessageAt,
            boolean pinned, Long pinnedAt, boolean archived) {
        return new ConversationSummary(
                id, type, "GROUP".equals(type) ? name : null, "preview", "sender",
                lastMessageAt, 1L, 0, 0L,
                "GROUP".equals(type) ? null : name, null, null, 0L, 0L,
                null, null, false, null, false, null, archived, false,
                pinned, pinnedAt, java.util.List.of(), false, false, "TEXT");
    }
}
