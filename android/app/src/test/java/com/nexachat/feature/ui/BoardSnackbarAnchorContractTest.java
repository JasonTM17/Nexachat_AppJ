package com.nexachat.feature.ui;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Retry snackbars on the reminder and poll boards must clear the add button. */
public final class BoardSnackbarAnchorContractTest {

    @Test
    public void reminderSnackbarsAnchorAboveTheAddButton() throws Exception {
        String body = read("com/nexachat/feature/reminder/ReminderFragment.java");
        assertTrue(body.contains("setAnchorView(binding.reminderAdd)"));
    }

    @Test
    public void pollSnackbarsAnchorAboveTheAddButton() throws Exception {
        String body = read("com/nexachat/feature/poll/PollFragment.java");
        assertTrue(body.contains("setAnchorView(binding.pollAdd)"));
    }

    private static String read(String relative) throws Exception {
        Path[] candidates = new Path[] {
                Path.of("src/main/java", relative),
                Path.of("app/src/main/java", relative),
                Path.of("android/app/src/main/java", relative)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing " + relative);
    }
}
