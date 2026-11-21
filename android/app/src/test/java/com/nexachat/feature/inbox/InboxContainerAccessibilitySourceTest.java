package com.nexachat.feature.inbox;

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
 * Pins the inbox fixes for issue #254's second sweep: the label-free scroll
 * surfaces ({@code conversationList}, {@code inboxFilterScroll}, the
 * active-now rail) stop being focus targets while staying visible to a11y
 * services, and the search pill's decorative icon gains the layout id the
 * a11y oracle needs to resolve it as decoration (#258's rid-based
 * suppression only matches id-carrying views).
 */
public final class InboxContainerAccessibilitySourceTest {

    @Test
    public void inboxFragment_suppressesLabelFreeContainers() throws Exception {
        String source = read("src/main/java/com/nexachat/feature/inbox/InboxFragment.java");
        int configure = source.indexOf("binding.conversationList.setLayoutManager(layoutManager);");
        assertTrue(configure >= 0);
        String window = source.substring(configure, configure + 900);
        assertSuppressed(window, "binding.conversationList");
        assertSuppressed(window, "binding.inboxFilterScroll");
        assertSuppressed(window, "binding.activeNowScroll");
    }

    @Test
    public void inboxLayout_marksSearchIconDecorativeWithAnId() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(locateLayout().toFile());
        assertImportantForAccessibilityNo(doc, "inboxSearchIcon");
        Element scroll = viewById(doc, "activeNowScroll");
        assertTrue("the active-now rail must keep an id for the suppression call",
                scroll != null && "HorizontalScrollView".equals(
                        scroll.getTagName().substring(scroll.getTagName().indexOf(':') + 1)));
    }

    private static void assertSuppressed(String window, String target) {
        int at = window.indexOf("A11yContainerRules.suppressFocusTarget(\n                " + target);
        assertTrue(target + " must be dropped from the focus-target set", at >= 0);
    }

    private static Element viewById(Document doc, String id) {
        NodeList views = doc.getElementsByTagName("*");
        for (int i = 0; i < views.getLength(); i++) {
            Element view = (Element) views.item(i);
            if (view.getAttribute("android:id").endsWith("/" + id)) {
                return view;
            }
        }
        return null;
    }

    private static void assertImportantForAccessibilityNo(Document doc, String id) {
        Element view = viewById(doc, id);
        if (view == null) {
            throw new AssertionError("Missing view id " + id + " in fragment_inbox.xml");
        }
        assertTrue(view.getAttribute("android:importantForAccessibility") + " on " + id,
                "no".equals(view.getAttribute("android:importantForAccessibility")));
    }

    private static Path locateLayout() {
        return locate("src/main/res/layout/fragment_inbox.xml",
                "android/app/src/main/res/layout/fragment_inbox.xml",
                "app/src/main/res/layout/fragment_inbox.xml");
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(locate(
                relative,
                "android/app/" + relative,
                "app/" + relative)), StandardCharsets.UTF_8);
    }

    private static Path locate(String... candidates) {
        for (String relative : candidates) {
            Path path = Path.of(relative);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new AssertionError("Could not locate inbox a11y sources");
    }
}
