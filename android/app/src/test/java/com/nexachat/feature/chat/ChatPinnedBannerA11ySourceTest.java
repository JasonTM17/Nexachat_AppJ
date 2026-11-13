package com.nexachat.feature.chat;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class ChatPinnedBannerA11ySourceTest {

    @Test
    public void bindPinnedBanner_announcesTitleAndMessageOnRoot() throws Exception {
        String source = readChatInteractionsSource();
        assertTrue(source.contains("R.string.pinned_title"));
        assertTrue(source.contains("setContentDescription"));
        assertTrue(source.contains("\", \" + text"));
    }

    private static String readChatInteractionsSource() throws Exception {
        return new String(Files.readAllBytes(locate(
                "src/main/java/com/nexachat/feature/chat/ChatInteractionsViews.java",
                "android/app/src/main/java/com/nexachat/feature/chat/ChatInteractionsViews.java",
                "app/src/main/java/com/nexachat/feature/chat/ChatInteractionsViews.java")));
    }

    private static Path locate(String... candidates) {
        for (String relative : candidates) {
            Path path = Path.of(relative);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new AssertionError("Could not locate ChatInteractionsViews.java");
    }
}
