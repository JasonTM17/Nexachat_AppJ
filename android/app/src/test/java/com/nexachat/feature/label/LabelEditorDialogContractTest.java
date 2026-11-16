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
 * The editor lives in {@link LabelEditorDialogFragment} so rotation cannot
 * destroy the form mid-edit.
 */
public final class LabelEditorDialogContractTest {

    @Test
    public void editorIsARealDialogFragmentSoRotationKeepsIt() throws Exception {
        String source = read("com/nexachat/feature/label/LabelEditorDialogFragment.java");
        assertTrue(source.contains("extends DialogFragment"));
        assertTrue(source.contains("onSaveInstanceState"));
        String parent = read("com/nexachat/feature/label/LabelDialogFragment.java");
        assertTrue(parent.contains("LabelEditorDialogFragment.show("));
    }

    @Test
    public void editorFormScrollsVertically() throws Exception {
        String onCreateDialog = methodBody("public Dialog onCreateDialog");
        assertTrue(onCreateDialog.contains("FormDialogs.scrollableBody(form)"));
        String helper = read("com/nexachat/feature/ui/FormDialogs.java");
        assertTrue(helper.contains("new ScrollView"));
        assertTrue(helper.contains("ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT"));
    }

    @Test
    public void colourChipsWrapInsideAChipGroup() throws Exception {
        String onCreateDialog = methodBody("public Dialog onCreateDialog");
        assertTrue(onCreateDialog.contains("new ChipGroup"));
        assertTrue(onCreateDialog.contains("colorGroup.setSingleLine(false)"));
        assertFalse(onCreateDialog.contains("colorGroup.setSingleLine(true)"));
        assertFalse(onCreateDialog.contains("HorizontalScrollView"));
    }

    @Test
    public void editorRowsHaveNoFixedWidths() throws Exception {
        String onCreateDialog = methodBody("public Dialog onCreateDialog");
        assertFalse(Pattern.compile("LayoutParams\\(\\s*\\d").matcher(onCreateDialog).find());
        assertFalse(Pattern.compile("setMinimumWidth|setWidth\\(").matcher(onCreateDialog).find());
    }

    private static String methodBody(String signature) throws Exception {
        String source = read("com/nexachat/feature/label/LabelEditorDialogFragment.java");
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
