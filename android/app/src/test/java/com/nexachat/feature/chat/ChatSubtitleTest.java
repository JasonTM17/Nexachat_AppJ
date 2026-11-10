package com.nexachat.feature.chat;

import com.nexachat.R;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChatSubtitleTest {

    @Test
    public void disconnectedOnlyWhenTheSessionIsOffline() {
        assertEquals(R.string.chat_offline, ChatSubtitle.textRes(false, false, true, null));
        assertEquals(R.string.chat_offline, ChatSubtitle.textRes(false, false, false, Boolean.FALSE));
    }

    @Test
    public void connectedGroupHidesTheBanner() {
        assertEquals(0, ChatSubtitle.textRes(false, true, true, null));
    }

    @Test
    public void connectedDirectPeerUsesPresenceNotTheNetworkBanner() {
        assertEquals(R.string.presence_active_now, ChatSubtitle.textRes(false, true, false, Boolean.TRUE));
        assertEquals(R.string.presence_offline, ChatSubtitle.textRes(false, true, false, Boolean.FALSE));
        assertEquals(0, ChatSubtitle.textRes(false, true, false, null));
    }

    @Test
    public void typingWinsOverConnectivity() {
        assertEquals(R.string.chat_typing, ChatSubtitle.textRes(true, false, false, Boolean.FALSE));
    }
}
