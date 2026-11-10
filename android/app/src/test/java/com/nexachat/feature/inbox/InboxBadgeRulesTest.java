package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.nexachat.data.ConversationSummary;
import com.nexachat.data.PeerPresence;
import org.junit.Test;

public final class InboxBadgeRulesTest {

    @Test
    public void formatUnreadCount_returnsEmptyWhenZeroOrNegative() {
        assertEquals("", InboxBadgeRules.formatUnreadCount(0));
        assertEquals("", InboxBadgeRules.formatUnreadCount(-3));
    }

    @Test
    public void formatUnreadCount_returnsExactValueUpTo99() {
        assertEquals("1", InboxBadgeRules.formatUnreadCount(1));
        assertEquals("42", InboxBadgeRules.formatUnreadCount(42));
        assertEquals("99", InboxBadgeRules.formatUnreadCount(99));
    }

    @Test
    public void formatUnreadCount_capsAt99PlusAbove99() {
        assertEquals("99+", InboxBadgeRules.formatUnreadCount(100));
        assertEquals("99+", InboxBadgeRules.formatUnreadCount(999));
    }

    @Test
    public void resolveOnlineStatus_doesNotFabricateOnlineForUnknownDirectPresence() {
        ConversationSummary directUnknown = summary("c1", false, false, false, null);
        assertNull(InboxBadgeRules.resolveOnlineStatus(directUnknown));

        ConversationSummary directOffline = summary(
                "c2", false, false, false, new PeerPresence(false, 1000L));
        assertEquals(Boolean.FALSE, InboxBadgeRules.resolveOnlineStatus(directOffline));

        ConversationSummary directOnline = summary(
                "c3", false, false, false, new PeerPresence(true, null));
        assertEquals(Boolean.TRUE, InboxBadgeRules.resolveOnlineStatus(directOnline));

        ConversationSummary groupOnline = summary(
                "c4", true, false, false, new PeerPresence(true, null));
        assertNull(InboxBadgeRules.resolveOnlineStatus(groupOnline));
    }

    @Test
    public void resolveSwipeActions_reflectPinnedAndArchivedState() {
        ConversationSummary normal = summary("c1", false, false, false, null);
        assertEquals(InboxBadgeRules.SwipeAction.PIN, InboxBadgeRules.resolveStartSwipeAction(normal));
        assertEquals(InboxBadgeRules.SwipeAction.ARCHIVE, InboxBadgeRules.resolveEndSwipeAction(normal));

        ConversationSummary pinnedAndArchived = summary("c2", false, true, true, null);
        assertEquals(
                InboxBadgeRules.SwipeAction.UNPIN,
                InboxBadgeRules.resolveStartSwipeAction(pinnedAndArchived));
        assertEquals(
                InboxBadgeRules.SwipeAction.UNARCHIVE,
                InboxBadgeRules.resolveEndSwipeAction(pinnedAndArchived));
    }

    private static ConversationSummary summary(
            String id,
            boolean group,
            boolean pinned,
            boolean archived,
            PeerPresence presence
    ) {
        return new ConversationSummary(
                id,
                group ? "GROUP" : "DIRECT",
                group ? "Group " + id : null,
                "Hello",
                "Peer " + id,
                1_700_000_000_000L,
                1L,
                0,
                "Peer " + id,
                null,
                null,
                0L,
                0L,
                null,
                "u1",
                false,
                presence,
                false,
                null,
                archived,
                false,
                pinned,
                pinned ? 1_700_000_000_000L : null
        );
    }
}
