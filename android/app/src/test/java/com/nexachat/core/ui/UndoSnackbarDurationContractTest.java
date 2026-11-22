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
 * Every Snackbar that offers an Undo action must stay on screen long enough
 * for the action to be reached: {@link SnackbarDurations#UNDO_MS} (5 s)
 * instead of the default LENGTH_SHORT/LENGTH_LONG windows (~1.5–2.75 s) that
 * were too short to tap in archive-undo journeys.
 */
public final class UndoSnackbarDurationContractTest {

    private static final List<String> UNDO_SNACKBAR_FILES = List.of(
            "src/main/java/com/nexachat/feature/inbox/InboxFragment.java",
            "src/main/java/com/nexachat/feature/chat/ChatFragment.java",
            "src/main/java/com/nexachat/feature/group/GroupDetailFragment.java");

    @Test
    public void everyUndoActionSnackbarUsesTheLongDuration() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String file : UNDO_SNACKBAR_FILES) {
            String source = read(file);
            int cursor = 0;
            while (true) {
                int at = source.indexOf("setAction(R.string.action_undo", cursor);
                if (at < 0) {
                    break;
                }
                cursor = at + 1;
                String window = source.substring(Math.max(0, at - 400), at);
                if (!window.contains("setDuration(SnackbarDurations.UNDO_MS)")) {
                    int line = 1;
                    for (int i = 0; i < at; i++) {
                        if (source.charAt(i) == '\n') {
                            line++;
                        }
                    }
                    missing.add(file + ':' + line);
                }
            }
        }
        if (!missing.isEmpty()) {
            fail("action_undo snackbars without the long duration: " + missing);
        }
    }

    @Test
    public void undoDurationExceedsLengthLong() throws Exception {
        assertTrue("Undo window must exceed LENGTH_LONG (~2.75s)",
                SnackbarDurations.UNDO_MS > 2750);
    }

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(Path.of(relative)), StandardCharsets.UTF_8);
    }
}
