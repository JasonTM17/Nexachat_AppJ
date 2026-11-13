package com.nexachat.feature.chat;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards the chat list's visible message and pixel offset across list submissions. */
public final class ChatScrollAnchorContractTest {

    @Test
    public void restoresTheFirstVisibleMessageAtItsPreviousOffset() throws Exception {
        String source = new String(Files.readAllBytes(
                Path.of("src/main/java/com/nexachat/feature/chat/ChatFragment.java")), StandardCharsets.UTF_8);

        assertTrue("The first visible row's decorated offset must be captured",
                source.contains("getDecoratedTop(anchorView) - binding.messageList.getPaddingTop()"));
        assertTrue("The stable row must be restored with its previous offset",
                source.contains("scrollToPositionWithOffset(i, stableAnchorOffset)"));
        assertFalse("Position-only restore snaps the anchor to the top",
                source.contains("binding.messageList.scrollToPosition(i)"));
    }
}
