package com.nexachat.feature.profile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/**
 * The Google link/unlink password dialog must paint its whole surface and lift
 * above the keyboard through window resizing. Padding the decor window by the
 * full IME height pushed the title and buttons out of the visible band.
 */
public final class LinkedAccountsDialogContractTest {

    @Test
    public void dialogResizesAboveTheImeWithoutDecorPadding() throws Exception {
        String body = methodBody("private void showGooglePasswordDialog(");
        assertFalse("decor insets hide the dialog title and buttons",
                body.contains("ImeInsets.applyToDecor"));
        assertTrue("the dialog must resize above the IME",
                body.contains("FormDialogs.resizeAboveIme"));
    }

    private static String methodBody(String signature) throws Exception {
        String source = readSource();
        int start = source.indexOf(signature);
        assertTrue("missing method " + signature, start >= 0);
        int end = source.indexOf("\n    private ", start + signature.length());
        return end > start ? source.substring(start, end) : source.substring(start);
    }

    private static String readSource() throws Exception {
        return readFile(new String[]{
                "src/main/java/com/nexachat/feature/profile/ProfileFragment.java",
                "app/src/main/java/com/nexachat/feature/profile/ProfileFragment.java",
                "android/app/src/main/java/com/nexachat/feature/profile/ProfileFragment.java"});
    }

    private static String readFile(String[] candidates) throws Exception {
        for (String path : candidates) {
            File file = new File(path);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Could not locate " + candidates[0]);
    }
}
