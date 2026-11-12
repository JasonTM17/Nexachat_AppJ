package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Auth screens must use the Note mark ({@code ic_logo_mark}) only. */
public final class AuthLogoLayoutContractTest {

    private static final Pattern AUTH_LOGO_MARK = Pattern.compile(
            "android:id=\"@\\+id/authLogo\"[^>]*android:src=\"@drawable/([^\"]+)\"",
            Pattern.DOTALL);
    private static final Pattern AUTH_LOGO_MARK_ALT = Pattern.compile(
            "android:src=\"@drawable/([^\"]+)\"[^>]*android:id=\"@\\+id/authLogo\"",
            Pattern.DOTALL);
    private static final Set<String> AUTH_LAYOUTS = Set.of(
            "fragment_login.xml",
            "fragment_register.xml",
            "fragment_complete_profile.xml",
            "fragment_splash.xml",
            "fragment_forgot_password.xml");

    private File layoutDir;

    @Before
    public void setUp() {
        File nested = new File("app/src/main/res/layout");
        if (nested.isDirectory()) {
            layoutDir = nested;
            return;
        }
        File top = new File("src/main/res/layout");
        if (top.isDirectory()) {
            layoutDir = top;
            return;
        }
        throw new IllegalStateException("Could not locate layout directory");
    }

    @Test
    public void authLayoutsOnlyReferenceTheNoteMarkLogo() throws Exception {
        for (String name : AUTH_LAYOUTS) {
            File file = new File(layoutDir, name);
            assertTrue(name + " missing", file.isFile());
            String xml = read(file);
            assertAuthLogo(xml, AUTH_LOGO_MARK, name);
            assertAuthLogo(xml, AUTH_LOGO_MARK_ALT, name);
        }
    }

    @Test
    public void forbiddenLegacyLogoFilesStayAbsent() {
        File res = layoutDir.getParentFile();
        assertFalse(new File(res, "drawable/ic_nexachat_logo.xml").exists());
    }

    private static void assertAuthLogo(String xml, Pattern pattern, String layoutName) {
        Matcher matcher = pattern.matcher(xml);
        if (!matcher.find()) {
            return;
        }
        assertEquals(layoutName + " must use ic_logo_mark", "ic_logo_mark", matcher.group(1));
    }

    private static String read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
