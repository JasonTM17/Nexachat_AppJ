package com.nexachat.feature.ui;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Text-entry dialogs outside the host inset padding must move above the keyboard. */
public final class FormSheetKeyboardContractTest {

    @Test
    public void pollAndReminderSheetsExpandAndPadForTheIme() throws Exception {
        String poll = read("com/nexachat/feature/poll/PollCreateSheet.java");
        String reminder = read("com/nexachat/feature/reminder/ReminderEditorSheet.java");
        assertTrue(poll.contains("ImeInsets.applyBottomPadding"));
        assertTrue(poll.contains("STATE_EXPANDED"));
        assertTrue(reminder.contains("ImeInsets.applyBottomPadding"));
        assertTrue(reminder.contains("STATE_EXPANDED"));
    }

    @Test
    public void passwordAndLabelEditorsLiftTheirWindowsAboveTheIme() throws Exception {
        String profile = read("com/nexachat/feature/profile/ProfileFragment.java");
        String labels = read("com/nexachat/feature/label/LabelDialogFragment.java");
        assertTrue(profile.contains("ImeInsets.applyToDecor"));
        assertTrue(labels.contains("ImeInsets.applyToDecor"));
    }

    @Test
    public void bottomOffsetUsesTheLargerOfTheBarAndTheKeyboard() {
        assertTrue(ImeInsets.bottomOffset(12, 48, 0) == 60);
        assertTrue(ImeInsets.bottomOffset(12, 48, 320) == 332);
        assertTrue(ImeInsets.bottomOffset(0, 24, 24) == 24);
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
