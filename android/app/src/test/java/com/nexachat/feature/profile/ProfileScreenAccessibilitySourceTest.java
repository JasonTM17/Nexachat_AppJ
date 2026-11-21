package com.nexachat.feature.profile;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Pins the settings-screen fixes for issue #254's second sweep: the
 * label-free settings column (a NestedScrollView) stops being a focus target
 * while staying visible to a11y services. The row descriptions
 * ({@code languageRow}/{@code themeRow}) come from the shared SettingsRowView
 * compose pinned in core.ui.components.
 */
public final class ProfileScreenAccessibilitySourceTest {

    @Test
    public void profileFragment_suppressesTheLabelFreeColumn() throws Exception {
        String source = read("src/main/java/com/nexachat/feature/profile/ProfileFragment.java");
        int at = source.indexOf("binding.profileToolbar.setNavigationOnClickListener");
        assertTrue(at >= 0);
        String window = source.substring(at, at + 800);
        assertTrue("profileScroll must be dropped from the focus-target set",
                window.contains("A11yContainerRules.suppressFocusTarget(\n                binding.profileScroll"));
    }

    @Test
    public void profileLayout_exposesTheScrollId() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(locateLayout().toFile());
        NodeList views = doc.getElementsByTagName("*");
        boolean found = false;
        for (int i = 0; i < views.getLength(); i++) {
            Element view = (Element) views.item(i);
            if (view.getAttribute("android:id").endsWith("/profileScroll")) {
                found = true;
                break;
            }
        }
        assertTrue("fragment_profile.xml must carry @+id/profileScroll", found);
    }

    private static Path locateLayout() {
        for (String relative : new String[] {
                "src/main/res/layout/fragment_profile.xml",
                "android/app/src/main/res/layout/fragment_profile.xml",
                "app/src/main/res/layout/fragment_profile.xml"}) {
            Path path = Path.of(relative);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new AssertionError("Could not locate fragment_profile.xml");
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
