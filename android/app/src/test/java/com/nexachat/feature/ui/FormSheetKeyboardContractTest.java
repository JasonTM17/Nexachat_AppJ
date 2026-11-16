package com.nexachat.feature.ui;

import static org.junit.Assert.assertFalse;
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
        // The reminder sheet inherits expand/resize/max-width from the shared scaffold.
        assertTrue(reminder.contains("NexaBottomSheetFragment"));
        String scaffold = read("com/nexachat/feature/ui/NexaBottomSheetFragment.java");
        assertTrue(scaffold.contains("STATE_EXPANDED"));
        assertTrue(scaffold.contains("SOFT_INPUT_ADJUST_RESIZE"));
        assertTrue(scaffold.contains("sheet_max_width"));
    }

    @Test
    public void passwordAndLabelEditorsLiftTheirWindowsAboveTheIme() throws Exception {
        String profile = read("com/nexachat/feature/profile/ProfileFragment.java");
        String labelEditor = read("com/nexachat/feature/label/LabelEditorDialogFragment.java");
        String helper = read("com/nexachat/feature/ui/FormDialogs.java");
        assertTrue(profile.contains("FormDialogs.resizeAboveIme"));
        assertTrue(labelEditor.contains("FormDialogs.resizeAboveIme"));
        // All text-entry dialogs route through the shared helper, which is the
        // one place that resizes the window above the IME instead of padding
        // the decor and clipping the title and buttons.
        assertTrue(helper.contains("SOFT_INPUT_ADJUST_RESIZE"));
        assertFalse(labelEditor.contains("ImeInsets.applyToDecor"));
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
