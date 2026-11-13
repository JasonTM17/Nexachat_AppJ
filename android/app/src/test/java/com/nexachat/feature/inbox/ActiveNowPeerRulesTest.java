package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.nexachat.data.ConversationSummary;
import com.nexachat.data.PeerPresence;
import java.util.List;
import org.junit.Test;

public final class ActiveNowPeerRulesTest {

    @Test
    public void selectPeers_keepsOnlyOnlineDirectConversations() {
        List<ConversationSummary> rows = List.of(
                direct("c1", "u1", "Alpha", new PeerPresence(true, null)),
                direct("c2", "u2", "Beta", new PeerPresence(false, 1L)),
                direct("c3", "u3", "Gamma", null),
                group("g1", "Squad", new PeerPresence(true, null)),
                direct("c4", "u4", "Delta", new PeerPresence(true, null)));

        List<ConversationSummary> peers = ActiveNowPeerRules.selectOnlineDirectPeers(rows);

        assertEquals(2, peers.size());
        assertEquals("c1", peers.get(0).getId());
        assertEquals("c4", peers.get(1).getId());
    }

    @Test
    public void selectPeers_deduplicatesByPeerIdentityNotTitle() {
        List<ConversationSummary> rows = List.of(
                direct("c1", "u-shared", "Audit User", new PeerPresence(true, null)),
                direct("c2", "u-shared", "Audit Backup", new PeerPresence(true, null)),
                direct("c3", "u-other", "Audit User", new PeerPresence(true, null)));

        List<ConversationSummary> peers = ActiveNowPeerRules.selectOnlineDirectPeers(rows);

        assertEquals(2, peers.size());
        assertEquals("c1", peers.get(0).getId());
        assertEquals("u-shared", peers.get(0).getOtherUserId());
        assertEquals("c3", peers.get(1).getId());
    }

    @Test
    public void selectPeers_respectsMaxVisibleCount() {
        java.util.ArrayList<ConversationSummary> rows = new java.util.ArrayList<>();
        for (int index = 0; index < ActiveNowPeerRules.MAX_VISIBLE_PEERS + 2; index++) {
            rows.add(direct(
                    "c" + index,
                    "u" + index,
                    "Peer " + index,
                    new PeerPresence(true, null)));
        }

        List<ConversationSummary> peers = ActiveNowPeerRules.selectOnlineDirectPeers(rows);

        assertEquals(ActiveNowPeerRules.MAX_VISIBLE_PEERS, peers.size());
    }

    @Test
    public void peerIdentityKey_fallsBackToConversationIdWhenPeerIdMissing() {
        ConversationSummary row = direct("conv-only", null, "Solo", new PeerPresence(true, null));
        assertEquals("conv-only", ActiveNowPeerRules.peerIdentityKey(row));
    }

    @Test
    public void captionMaxLines_allowsTwoLinesForStoryRail() {
        assertEquals(2, ActiveNowPeerRules.captionMaxLines());
        assertTrue(ActiveNowPeerRules.captionMaxLines() >= 2);
    }

    private static ConversationSummary direct(
            String id,
            String otherUserId,
            String name,
            PeerPresence presence) {
        return new ConversationSummary(
                id,
                "DIRECT",
                null,
                "Hello",
                name,
                1_700_000_000_000L,
                1L,
                0,
                name,
                null,
                null,
                0L,
                0L,
                null,
                otherUserId,
                false,
                presence,
                false,
                null,
                false,
                false,
                false,
                null);
    }

    private static ConversationSummary group(String id, String title, PeerPresence presence) {
        return new ConversationSummary(
                id,
                "GROUP",
                title,
                "Hello",
                "Sender",
                1_700_000_000_000L,
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
                presence,
                false,
                null,
                false,
                false,
                false,
                null);
    }
}
