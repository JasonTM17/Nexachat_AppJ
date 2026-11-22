package com.nexachat.core.ui;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Every Snackbar shown on a screen whose layout carries a FAB must anchor to a
 * bottom view (the FAB or the composer bar) so the action row is not covered
 * by the floating button. The regression is the inbox Undo/Retry snackbar
 * rendered under createGroupFab.
 */
public final class SnackbarFabAnchorSourceTest {

    private static final List<String> SCREENS_WITH_FAB = List.of(
            "src/main/java/com/nexachat/feature/inbox/InboxFragment.java",
            "src/main/java/com/nexachat/feature/chat/ChatFragment.java",
            "src/main/java/com/nexachat/feature/feed/PublicFeedFragment.java",
            "src/main/java/com/nexachat/feature/poll/PollFragment.java",
            "src/main/java/com/nexachat/feature/reminder/ReminderFragment.java");

    /** Dialog-scoped snackbars host their own anchor and are exempt. */
    private static final String DIALOG_HOST = "Snackbar.make(content";

    @Test
    public void everySnackbarOnFabScreensIsAnchored() throws Exception {
        List<String> unanchored = new ArrayList<>();
        for (String file : SCREENS_WITH_FAB) {
            String source = read(file);
            int cursor = 0;
            while (true) {
                int at = source.indexOf("Snackbar.make(", cursor);
                if (at < 0) {
                    break;
                }
                cursor = at + 1;
                if (source.startsWith(DIALOG_HOST, at)) {
                    continue;
                }
                String window = source.substring(at, Math.min(source.length(), at + 700));
                if (!window.contains("setAnchorView(")) {
                    int line = 1;
                    for (int i = 0; i < at; i++) {
                        if (source.charAt(i) == '\n') {
                            line++;
                        }
                    }
                    unanchored.add(file + ':' + line);
                }
            }
        }
        if (!unanchored.isEmpty()) {
            fail("Snackbar.make calls without setAnchorView: " + unanchored);
        }
    }

    @Test
    public void inboxSnackbarAnchorsToNewChatFab() throws Exception {
        String source = read(
                "src/main/java/com/nexachat/feature/inbox/InboxFragment.java");
        assertTrue("inbox snackbars must anchor to createGroupFab",
                source.contains("setAnchorView(binding.createGroupFab)"));
    }

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(Path.of(relative)), StandardCharsets.UTF_8);
    }
}
