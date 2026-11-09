package com.nexachat.feature.inbox;

import com.nexachat.data.ConversationSummary;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class InboxFilterRulesTest {

    @Test
    public void allKeepsRowsAndTheirOrder() {
        List<ConversationSummary> rows = sampleRows();

        assertEquals(rows, InboxFilterRules.apply(rows, InboxFilter.ALL));
    }

    @Test
    public void unreadKeepsOnlyRowsWithUnreadMessages() {
        List<ConversationSummary> filtered =
                InboxFilterRules.apply(sampleRows(), InboxFilter.UNREAD);

        assertEquals(List.of("direct-unread", "group-unread"),
                filtered.stream().map(ConversationSummary::getId).toList());
    }

    @Test
    public void groupsKeepsOnlyGroupRows() {
        List<ConversationSummary> filtered =
                InboxFilterRules.apply(sampleRows(), InboxFilter.GROUPS);

        assertEquals(List.of("group-unread", "group-read"),
                filtered.stream().map(ConversationSummary::getId).toList());
    }

    @Test
    public void filtersReturnAnEmptyListWhenNoRowsMatch() {
        assertTrue(InboxFilterRules.apply(
                List.of(conversation("direct-read", 0, false)), InboxFilter.UNREAD).isEmpty());
    }

    @Test
    public void defaultFiltersHideArchivedRows() {
        List<ConversationSummary> rows = List.of(
                conversation("active", 1, false),
                archived("archived", 1, false));

        assertEquals(List.of("active"), ids(InboxFilterRules.apply(rows, InboxFilter.ALL)));
        assertEquals(List.of("active"), ids(InboxFilterRules.apply(rows, InboxFilter.UNREAD)));
    }

    @Test
    public void archivedFilterKeepsOnlyArchivedRows() {
        List<ConversationSummary> rows = List.of(
                conversation("active", 0, false),
                archived("archived", 0, false),
                archived("archived-group", 0, true));

        assertEquals(List.of("archived", "archived-group"),
                ids(InboxFilterRules.apply(rows, InboxFilter.ARCHIVED)));
    }

    @Test
    public void groupsFilterIgnoresArchivedGroups() {
        List<ConversationSummary> rows = List.of(
                conversation("group", 0, true),
                archived("archived-group", 0, true));

        assertEquals(List.of("group"), ids(InboxFilterRules.apply(rows, InboxFilter.GROUPS)));
    }

    private static List<String> ids(List<ConversationSummary> rows) {
        return rows.stream().map(ConversationSummary::getId).toList();
    }

    private static ConversationSummary archived(String id, int unread, boolean group) {
        return new ConversationSummary(
                id,
                group ? "GROUP" : "DIRECT",
                group ? "Group " + id : null,
                "last " + id,
                "Peer " + id,
                1_700_000_000_000L,
                42L,
                unread,
                "Peer " + id,
                null,
                null,
                0L,
                0L,
                null,
                null,
                false,
                null,
                false,
                null,
                true,
                false);
    }

    private static List<ConversationSummary> sampleRows() {
        return List.of(
                conversation("direct-unread", 2, false),
                conversation("group-unread", 1, true),
                conversation("direct-read", 0, false),
                conversation("group-read", 0, true));
    }

    private static ConversationSummary conversation(String id, int unread, boolean group) {
        return new ConversationSummary(
                id,
                group ? "GROUP" : "DIRECT",
                group ? "Group " + id : null,
                "last " + id,
                "Peer " + id,
                1_700_000_000_000L,
                42L,
                unread,
                "Peer " + id,
                null,
                null,
                0L,
                null,
                null,
                false,
                null);
    }
}
