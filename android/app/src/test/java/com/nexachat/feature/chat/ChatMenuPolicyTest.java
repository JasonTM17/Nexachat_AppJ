package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.R;
import com.nexachat.data.ConversationSummary;
import java.util.List;
import org.junit.Test;

public final class ChatMenuPolicyTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void hydratedArchivedGroupRowWinsOverDefaultChatState() {
        // First open of an archived group: the inbox row is hydrated while the
        // chat UiState still carries its defaults.
        ChatFragment.ChatMenuPolicy.Flags flags = ChatFragment.ChatMenuPolicy.resolve(
                conversation("GROUP", false, false, true, false, null),
                false, false, false, NOW);

        assertTrue(flags.group);
        assertTrue(flags.archived);
        assertFalse(flags.pinned);
        assertFalse(flags.e2ee);
        assertFalse(flags.muted);
    }

    @Test
    public void staleChatStateCannotOverrideAHydratedRow() {
        // After a toggle the chat state can lag the row, so a hydrated row wins
        // even when the state flags disagree upward.
        ChatFragment.ChatMenuPolicy.Flags flags = ChatFragment.ChatMenuPolicy.resolve(
                conversation("DIRECT", false, false, false, false, null),
                true, true, true, NOW);

        assertFalse(flags.group);
        assertFalse(flags.pinned);
        assertFalse(flags.e2ee);
    }

    @Test
    public void missingRowFallsBackToChatState() {
        ChatFragment.ChatMenuPolicy.Flags flags = ChatFragment.ChatMenuPolicy.resolve(
                null, true, true, true, NOW);

        assertTrue(flags.group);
        assertTrue(flags.pinned);
        assertTrue(flags.e2ee);
        // Mute and archive exist only on the row; without it they read false.
        assertFalse(flags.muted);
        assertFalse(flags.archived);
    }

    @Test
    public void missingRowAndMissingStateResolvesAllFalse() {
        ChatFragment.ChatMenuPolicy.Flags flags = ChatFragment.ChatMenuPolicy.resolve(
                null, false, false, false, NOW);

        assertFalse(flags.group);
        assertFalse(flags.pinned);
        assertFalse(flags.e2ee);
        assertFalse(flags.muted);
        assertFalse(flags.archived);
    }

    @Test
    public void groupFlagFollowsTheRowType() {
        assertTrue(ChatFragment.ChatMenuPolicy.resolve(
                conversation("GROUP", false, false, false, false, null),
                false, false, false, NOW).group);
        assertFalse(ChatFragment.ChatMenuPolicy.resolve(
                conversation("DIRECT", false, false, false, false, null),
                false, false, false, NOW).group);
    }

    @Test
    public void e2eeFlagFollowsTheRowThenTheState() {
        assertTrue(ChatFragment.ChatMenuPolicy.resolve(
                conversation("DIRECT", false, true, false, false, null),
                false, false, false, NOW).e2ee);
        // A hydrated row reporting clear wins over a stale state flag.
        assertFalse(ChatFragment.ChatMenuPolicy.resolve(
                conversation("DIRECT", false, false, false, false, null),
                false, false, true, NOW).e2ee);
        // Without the row the state flag still applies.
        assertTrue(ChatFragment.ChatMenuPolicy.resolve(
                null, false, false, true, NOW).e2ee);
    }

    @Test
    public void e2eePredicateLetsTheRowWinOverTheState() {
        assertTrue(ChatFragment.ChatMenuPolicy.e2ee(
                conversation("DIRECT", false, true, false, false, null), false));
        assertFalse(ChatFragment.ChatMenuPolicy.e2ee(
                conversation("DIRECT", false, false, false, false, null), true));
        assertTrue(ChatFragment.ChatMenuPolicy.e2ee(null, true));
        assertFalse(ChatFragment.ChatMenuPolicy.e2ee(null, false));
    }

    @Test
    public void pinFlagFollowsTheRowThenTheState() {
        assertTrue(ChatFragment.ChatMenuPolicy.resolve(
                conversation("DIRECT", true, false, false, false, null),
                false, false, false, NOW).pinned);
        assertFalse(ChatFragment.ChatMenuPolicy.resolve(
                conversation("DIRECT", false, false, false, false, null),
                false, true, false, NOW).pinned);
        assertTrue(ChatFragment.ChatMenuPolicy.resolve(
                null, false, true, false, NOW).pinned);
    }

    @Test
    public void mutedRowReadsItsExpiryAgainstTheClock() {
        assertTrue(ChatFragment.ChatMenuPolicy.resolve(
                conversation("DIRECT", false, false, false, true, NOW + 1_000L),
                false, false, false, NOW).muted);
        assertFalse(ChatFragment.ChatMenuPolicy.resolve(
                conversation("DIRECT", false, false, false, true, NOW - 1_000L),
                false, false, false, NOW).muted);
    }

    @Test
    public void pinLabelAndActionReadTheSameResolvedFlag() {
        // The label the menu shows and the mutation the tap sends are both
        // derived from flags.pinned, so they can never disagree.
        ChatFragment.ChatMenuPolicy.Flags pinned = ChatFragment.ChatMenuPolicy.resolve(
                conversation("DIRECT", true, false, false, false, null),
                false, false, false, NOW);
        assertEquals(R.string.action_unpin_conversation,
                ChatFragment.ChatMenuPolicy.pinLabelRes(pinned));
        assertFalse(pinned.pinTarget());

        ChatFragment.ChatMenuPolicy.Flags unpinned = ChatFragment.ChatMenuPolicy.resolve(
                conversation("DIRECT", false, false, false, false, null),
                false, true, false, NOW);
        assertEquals(R.string.action_pin_conversation,
                ChatFragment.ChatMenuPolicy.pinLabelRes(unpinned));
        assertTrue(unpinned.pinTarget());
    }

    private static ConversationSummary conversation(
            String type,
            boolean pinned,
            boolean e2ee,
            boolean archived,
            boolean muted,
            Long mutedUntilEpochMs) {
        return new ConversationSummary(
                "c-1", type, "GROUP".equals(type) ? "Group" : null, "last", "Peer",
                1_700_000_000_000L, 42L, 0, 0L, "Peer", null, null, 0L, 0L,
                null, "peer-1", false, null,
                muted, mutedUntilEpochMs, archived, false, pinned,
                pinned ? NOW : null, List.of(), e2ee, false, null);
    }
}
