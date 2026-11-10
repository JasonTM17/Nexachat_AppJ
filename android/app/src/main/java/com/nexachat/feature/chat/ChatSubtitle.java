package com.nexachat.feature.chat;

import com.nexachat.R;

/** Toolbar subtitle for a conversation. A lost connection and an offline peer are different states. */
public final class ChatSubtitle {
    private ChatSubtitle() {}

    /**
     * @param peerOnline null when this chat has no direct-peer presence (a group, or presence not loaded)
     * @return a string resource id, or 0 when the subtitle should be empty
     */
    public static int textRes(boolean peerTyping, boolean connected, boolean group, Boolean peerOnline) {
        if (peerTyping) return R.string.chat_typing;
        if (!connected) return R.string.chat_offline;
        if (!group && peerOnline != null) {
            return peerOnline.booleanValue() ? R.string.presence_active_now : R.string.presence_offline;
        }
        return 0;
    }
}
