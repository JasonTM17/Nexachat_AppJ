package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class InboxFilterChipColourRulesTest {

    @Test
    public void idleChipUsesTonalIdleRoles() {
        assertEquals("colorSurfaceContainer", InboxFilterChipColourRules.IDLE_FILL);
        assertEquals("nexa_pressed_surface", InboxFilterChipColourRules.IDLE_PRESSED_FILL);
        assertEquals("colorOutlineVariant", InboxFilterChipColourRules.IDLE_STROKE);
        assertEquals("colorOnSurfaceVariant", InboxFilterChipColourRules.IDLE_TEXT);
        assertEquals(1f, InboxFilterChipColourRules.IDLE_STROKE_DP, 0f);
    }

    @Test
    public void checkedChipUsesTonalContainerRoles() {
        assertEquals("colorPrimaryContainer", InboxFilterChipColourRules.CHECKED_FILL);
        assertEquals("colorOnPrimaryContainer", InboxFilterChipColourRules.CHECKED_ON_FILL);
        assertEquals("nexa_pressed_container", InboxFilterChipColourRules.CHECKED_PRESSED_FILL);
        assertEquals("colorPrimary", InboxFilterChipColourRules.CHECKED_STROKE);
    }

    @Test
    public void disabledChipUsesNexaDisabledRoles() {
        assertEquals("nexa_disabled_container", InboxFilterChipColourRules.DISABLED_FILL);
        assertEquals("nexa_on_disabled", InboxFilterChipColourRules.DISABLED_ON);
    }

    @Test
    public void applyNeverResolvesLegacyBrandRoles() throws Exception {
        String source = new String(Files.readAllBytes(locateRules()));
        assertFalse("checked chips must not resolve brand_fill",
                source.contains("R.color.brand_fill"));
        assertFalse("checked chips must not resolve brand_on_fill",
                source.contains("R.color.brand_on_fill"));
        assertFalse("checked chips must not resolve brand_pressed",
                source.contains("R.color.brand_pressed"));
    }

    private static Path locateRules() {
        for (String relative : new String[] {
                "src/main/java/com/nexachat/feature/inbox/InboxFilterChipColourRules.java",
                "android/app/src/main/java/com/nexachat/feature/inbox/InboxFilterChipColourRules.java",
                "app/src/main/java/com/nexachat/feature/inbox/InboxFilterChipColourRules.java"}) {
            Path path = Path.of(relative);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new AssertionError("Could not locate InboxFilterChipColourRules.java");
    }
}
