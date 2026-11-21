package com.nexachat.core.ui.components;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Pins the AvatarView accessibility contract behind issue #254's
 * missing_desc_images findings (inbox/active-now/row/profile photos): the
 * container's own description is the announcement, so the code-created photo,
 * initials and presence dot are decoration for a11y services — and the photo
 * still carries a description because API 36 uiautomator dumps keep
 * importantForAccessibility="no" views and carry no marker for them, so a
 * desc-less photo would read as an unnamed image to the sweep oracle.
 */
public final class AvatarAccessibilitySourceTest {

    @Test
    public void avatarView_hidesInitialsAndStatusDotFromAccessibility() throws Exception {
        String source = read("src/main/java/com/nexachat/core/ui/components/AvatarView.java");
        assertCallFollowsCreation(source, "initialsView = new TextView(context);",
                "initialsView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)");
        assertCallFollowsCreation(source, "statusDotView = new View(context);",
                "statusDotView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)");
    }

    @Test
    public void avatarView_photoIsDecorativeButCarriesTheDisplayName() throws Exception {
        String source = read("src/main/java/com/nexachat/core/ui/components/AvatarView.java");
        assertCallFollowsCreation(source, "imageView = new ImageView(context);",
                "imageView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)");
        int bind = source.indexOf("public void setName(String name)");
        assertTrue("AvatarView must keep setName", bind >= 0);
        String body = source.substring(bind, source.indexOf("public void setAvatarUrl"));
        assertTrue("setName must describe the container and mirror the same name onto the "
                        + "photo for flat dumps",
                body.contains("setContentDescription(displayName)"));
        assertTrue(body.indexOf("setContentDescription(displayName)")
                < body.indexOf("imageView.setContentDescription(displayName)"));
    }

    @Test
    public void containerRules_dropFocusTargetWithoutHidingTheContainer() throws Exception {
        String source = read("src/main/java/com/nexachat/core/ui/components/A11yContainerRules.java");
        int method = source.indexOf("public static void suppressFocusTarget");
        assertTrue("A11yContainerRules must expose suppressFocusTarget", method >= 0);
        String body = source.substring(method);
        assertTrue("suppressFocusTarget must clear view focusability for the dump oracle",
                body.contains("container.setFocusable(false)"));
        assertTrue("suppressFocusTarget must also clear touch-mode focusability",
                body.contains("container.setFocusableInTouchMode(false)"));
        assertTrue("the suppression must never hide the container from a11y services",
                !body.contains("setImportantForAccessibility"));
    }

    private static void assertCallFollowsCreation(String source, String creation, String call) {
        int created = source.indexOf(creation);
        assertTrue("expected " + creation, created >= 0);
        int end = source.indexOf("addView(", created);
        assertTrue(call + " must run before the view is attached", end >= 0);
        String window = source.substring(created, end);
        assertTrue(call + " must be set at construction", window.contains(call));
    }

    private static String read(String relative) throws Exception {
        for (String prefix : new String[] {"", "android/app/", "app/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Could not locate " + relative);
    }
}
