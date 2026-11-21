package com.nexachat.feature.chat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChatToolbarPresentationTest {

    @Test
    public void directChatKeepsCallAndHidesGroupInfo() {
        assertTrue(ChatFragment.ChatToolbarPolicy.callAvailable(false));
        assertFalse(ChatFragment.ChatToolbarPolicy.infoAvailable(false));
        assertTrue(ChatFragment.ChatToolbarPolicy.searchAvailable());
        assertTrue(ChatFragment.ChatToolbarPolicy.summaryAvailable());
    }

    @Test
    public void groupChatKeepsInfoAndHidesDirectCall() {
        assertFalse(ChatFragment.ChatToolbarPolicy.callAvailable(true));
        assertTrue(ChatFragment.ChatToolbarPolicy.infoAvailable(true));
    }

    @Test
    public void headerAvatarStaysOnNarrowWidthAndYieldsOnlyAtLargeType() {
        assertFalse(ChatFragment.ChatToolbarPolicy.hideHeaderAvatar(1.0f));
        assertFalse(ChatFragment.ChatToolbarPolicy.hideHeaderAvatar(1.49f));
        assertTrue(ChatFragment.ChatToolbarPolicy.hideHeaderAvatar(1.5f));
    }

    @Test
    public void compactWidthMovesVideoOutOfTheToolbar() {
        assertTrue(ChatFragment.ChatToolbarPolicy.compactToolbarActions(320));
        assertTrue(ChatFragment.ChatToolbarPolicy.compactToolbarActions(359));
        assertFalse(ChatFragment.ChatToolbarPolicy.compactToolbarActions(360));
        assertFalse(ChatFragment.ChatToolbarPolicy.compactToolbarActions(411));
    }

    @Test
    public void titleMarginStartKeepsTitleClearOfHeaderAvatar() {
        int withAvatar = ChatFragment.ChatToolbarPolicy.titleMarginStartDp(false);
        assertTrue(withAvatar > 0);
        assertEquals(48, withAvatar);
        assertEquals(16, ChatFragment.ChatToolbarPolicy.titleMarginStartDp(true));
        // Navigation back button ends at ~58dp from the toolbar start (48dp touch target + icon inset).
        int toolbarNavButtonEndDp = 58;
        int titleStartDp = toolbarNavButtonEndDp + withAvatar;
        assertTrue(titleStartDp > ChatFragment.ChatToolbarPolicy.avatarOccupiedWidthEndDp());
        assertTrue(titleStartDp < 120);
    }
}
