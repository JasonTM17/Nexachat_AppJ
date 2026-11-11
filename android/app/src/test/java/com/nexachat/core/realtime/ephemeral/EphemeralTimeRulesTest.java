package com.nexachat.core.realtime.ephemeral;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.data.PeerPresence;
import java.util.List;
import org.junit.Test;

public final class EphemeralTimeRulesTest {
    @Test
    public void presenceStaysOnlineWhileAnyDeviceLeaseIsCurrent() {
        PeerPresence presence = EphemeralTimeRules.aggregatePresence(List.of(
                new EphemeralTimeRules.PresenceRecord(true, null, 20_000L),
                new EphemeralTimeRules.PresenceRecord(false, 19_000L, 0L)), 10_000L);

        assertEquals(new PeerPresence(true, null), presence);
    }

    @Test
    public void expiredOnlineDeviceDoesNotHideNewestOfflineLastSeen() {
        PeerPresence presence = EphemeralTimeRules.aggregatePresence(List.of(
                new EphemeralTimeRules.PresenceRecord(true, null, 9_000L),
                new EphemeralTimeRules.PresenceRecord(false, 8_000L, 0L),
                new EphemeralTimeRules.PresenceRecord(false, 9_500L, 0L)), 10_000L);

        assertEquals(new PeerPresence(false, 9_500L), presence);
    }

    @Test
    public void zeroedOfflineFirebaseMarkerDoesNotBecomeAnEpochLastSeen() {
        PeerPresence presence = EphemeralTimeRules.aggregatePresence(List.of(
                new EphemeralTimeRules.PresenceRecord(false, 0L, 0L)), 10_000L);

        assertEquals(new PeerPresence(false, null), presence);
    }

    @Test
    public void typingIsActiveOnlyBeforeAbsoluteExpiryAndTtlIsBounded() {
        assertTrue(EphemeralTimeRules.isTypingActive(5_001L, 5_000L));
        assertFalse(EphemeralTimeRules.isTypingActive(5_000L, 5_000L));
        assertEquals(2_000L, EphemeralTimeRules.typingTtl(3_000L, 5_000L));
        assertEquals(EphemeralTimeRules.TYPING_TTL_MS,
                EphemeralTimeRules.typingTtl(3_000L, 30_000L));
        assertEquals(0L, EphemeralTimeRules.typingTtl(5_000L, 4_000L));
    }

    @Test
    public void typingThrottleUsesElapsedTimeIncludingZero() {
        assertTrue(EphemeralTimeRules.canSendTyping(-1L, 0L));
        assertFalse(EphemeralTimeRules.canSendTyping(0L, 2_999L));
        assertTrue(EphemeralTimeRules.canSendTyping(0L, 3_000L));
    }

    @Test
    public void leaseListPrioritizesOpenChatAndNeverExceedsServerLimit() {
        java.util.Map<String, String> peers = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 35; index++) {
            peers.put("peer-" + index, "conversation-" + index);
        }
        EphemeralScope scope = EphemeralScope.empty()
                .withPresencePeers(peers)
                .withTypingConversation("open-chat", java.util.Set.of("peer-0"));

        List<String> leased = scope.leasedConversationIds();
        assertEquals("open-chat", leased.get(0));
        assertEquals(EphemeralScope.MAX_LEASED_CONVERSATIONS, leased.size());
        assertFalse(leased.contains("../unsafe"));
    }

    @Test
    public void unsafeFirebasePathComponentsAreExcluded() {
        EphemeralScope scope = EphemeralScope.empty()
                .withPresencePeers(java.util.Map.of("peer/unsafe", "conversation-safe"))
                .withTypingConversation("conversation.safe", java.util.Set.of("peer-safe"));

        assertTrue(scope.getPresenceConversationByUser().isEmpty());
        assertEquals(null, scope.getTypingConversationId());
        assertFalse(EphemeralScope.safePath("peer-\u0001"));
        assertFalse(EphemeralScope.safePath("x".repeat(769)));
    }
}
