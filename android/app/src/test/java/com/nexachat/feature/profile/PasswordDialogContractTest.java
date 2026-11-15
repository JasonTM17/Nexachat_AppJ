package com.nexachat.feature.profile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/**
 * The change-password dialog must paint its title and buttons and report errors on
 * a persistent inline line. Clipping the decor window padding hid the title and
 * button bar, and routing the error to the profile surface left the dialog blind.
 */
public final class PasswordDialogContractTest {

    @Test
    public void dialogKeepsItsWholeSurfaceVisible() throws Exception {
        String body = methodBody("private void showPasswordDialog()");
        assertFalse("decor insets hide the dialog title and buttons",
                body.contains("ImeInsets.applyToDecor"));
        assertTrue("the dialog must declare its title",
                body.contains("setTitle(R.string.profile_change_password)"));
    }

    @Test
    public void dialogShowsAPersistentInlineErrorLine() throws Exception {
        String body = methodBody("private void showPasswordDialog()");
        assertTrue("the form must own an error line",
                body.contains("R.id.password_dialog_error"));
        String source = readSource();
        assertTrue("the view model's password error must reach the dialog line",
                source.contains("passwordDialogError.setText"));
        assertTrue("the dialog stays open until the save resolves",
                source.contains("passwordDialog.dismiss()"));
    }

    @Test
    public void errorLineIdIsDeclared() throws Exception {
        String ids = readFile(new String[]{
                "src/main/res/values/ids.xml",
                "app/src/main/res/values/ids.xml",
                "android/app/src/main/res/values/ids.xml"});
        assertTrue(ids.contains("password_dialog_error"));
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
