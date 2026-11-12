package com.nexachat.feature.chat;

import org.junit.Test;

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
}
