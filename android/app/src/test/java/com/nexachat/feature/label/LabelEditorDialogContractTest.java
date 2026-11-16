package com.nexachat.feature.label;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The label create/edit dialog must fit its window: the form scrolls when it
 * is taller than the visible band, the colour chips wrap instead of running
 * past the dialog edges, and no fixed-width row can push the content wider
 * than the dialog. The clipping report showed the name field and both chip
 * rows overflowing the left/right edges and the action buttons cut off below.
 */
public final class LabelEditorDialogContractTest {

    @Test
    public void editorFormScrollsVertically() throws Exception {
        String openEditor = methodBody("private void openEditor");
        assertTrue(openEditor.contains("new ScrollView"));
        assertTrue(openEditor.contains("formScroll.addView(form)"));
        assertTrue(openEditor.contains("setView(formScroll)"));
    }

    @Test
    public void colourChipsWrapInsideAChipGroup() throws Exception {
        String openEditor = methodBody("private void openEditor");
        assertTrue(openEditor.contains("new ChipGroup"));
        assertTrue(openEditor.contains("colorGroup.setSingleLine(false)"));
        assertFalse(openEditor.contains("colorGroup.setSingleLine(true)"));
        assertFalse(openEditor.contains("HorizontalScrollView"));
    }

    @Test
    public void editorRowsHaveNoFixedWidths() throws Exception {
        String openEditor = methodBody("private void openEditor");
        assertFalse(Pattern.compile("LayoutParams\\(\\s*\\d").matcher(openEditor).find());
        assertFalse(Pattern.compile("setMinimumWidth|setWidth\\(").matcher(openEditor).find());
    }

    private static String methodBody(String signature) throws Exception {
        String source = read("com/nexachat/feature/label/LabelDialogFragment.java");
        int start = source.indexOf(signature);
        assertTrue("missing " + signature, start >= 0);
        int end = source.indexOf("\n    private ", start + signature.length());
        return end < 0 ? source.substring(start) : source.substring(start, end);
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
