package com.nexachat.feature.archive;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Pins the archive-screen fixes for issue #254's second sweep: the tab rail
 * (a TabLayout, i.e. a HorizontalScrollView), the filter rail and the archive
 * list are label-free structural containers — their tabs, buttons and rows
 * are the focus targets — so the containers stop being focus targets while
 * staying visible to a11y services.
 */
public final class ArchiveContainerAccessibilitySourceTest {

    @Test
    public void archiveFragment_suppressesLabelFreeContainers() throws Exception {
        String source = read("src/main/java/com/nexachat/feature/archive/ArchiveFragment.java");
        assertSuppressedNear(source, "private void configureTabs()", "binding.archiveTabs");
        assertSuppressedNear(source, "private void configureFilters()", "binding.archiveFiltersScroll");
        assertSuppressedNear(source, "private void configurePaging()", "binding.archiveList");
    }

    private static void assertSuppressedNear(String source, String anchor, String target) {
        int at = source.indexOf(anchor);
        assertTrue("expected " + anchor, at >= 0);
        String window = source.substring(at, at + 700);
        assertTrue(target + " must be dropped from the focus-target set in " + anchor,
                window.contains("A11yContainerRules.suppressFocusTarget(\n                " + target));
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
