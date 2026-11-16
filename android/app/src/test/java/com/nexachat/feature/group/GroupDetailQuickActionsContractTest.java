package com.nexachat.feature.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The group-detail quick actions are tonal tiles, not buttons inside the
 * members card: each tile is a reachable target with a labelled icon, and the
 * mute tile keeps its stateful icon and text ids for the binder.
 */
public final class GroupDetailQuickActionsContractTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String APP_NS = "http://schemas.android.com/apk/res-auto";

    @Test
    public void quickActionRowHoldsAddMemberAndMuteTiles() throws Exception {
        Element row = findById(loadLayout(), "quickActions");
        assertNotNull(row);
        assertNotNull(findById(row, "addMemberButton"));
        assertNotNull(findById(row, "muteTile"));
    }

    @Test
    public void tilesStayReachableAndLabelled() throws Exception {
        Element row = findById(loadLayout(), "quickActions");
        for (String id : new String[] {"addMemberButton", "muteTile"}) {
            Element tile = findById(row, id);
            assertNotNull(tile);
            assertEquals("true", attr(tile, "clickable"));
            assertEquals("@dimen/min_touch_target", attr(tile, "minHeight"));
        }
    }

    @Test
    public void muteTileKeepsStatefulIconAndText() throws Exception {
        Element tile = findById(loadLayout(), "muteTile");
        assertNotNull(tile);
        Element icon = findById(tile, "muteIcon");
        assertNotNull(icon);
        assertNotNull(findById(tile, "muteText"));
        assertEquals("no", attr(icon, "importantForAccessibility"));
    }

    @Test
    public void tilesShareOneAccentIconChip() throws Exception {
        Element row = findById(loadLayout(), "quickActions");
        for (String id : new String[] {"addMemberButton", "muteTile"}) {
            Element tile = findById(row, id);
            assertNotNull(tile);
            Element chip = (Element) tile.getElementsByTagName("FrameLayout").item(0);
            assertNotNull(id + " icon chip", chip);
            assertEquals(id + " chip backdrop",
                    "@drawable/bg_quick_action_icon_accent", attr(chip, "background"));
            assertEquals(id + " chip size",
                    "@dimen/icon_tile_size", attr(chip, "layout_width"));
            Element icon = (Element) chip.getElementsByTagName("ImageView").item(0);
            assertNotNull(id + " icon", icon);
            assertEquals(id + " icon tint",
                    "?attr/colorOnPrimaryContainer", attr(icon, "tint"));
        }
    }

    private static Document loadLayout() throws Exception {
        Path[] candidates = {
            Path.of("src/main/res/layout/fragment_group_detail.xml"),
            Path.of("app/src/main/res/layout/fragment_group_detail.xml"),
            Path.of("android/app/src/main/res/layout/fragment_group_detail.xml")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                return factory.newDocumentBuilder().parse(candidate.toFile());
            }
        }
        throw new AssertionError("Could not locate fragment_group_detail.xml");
    }

    private static Element findById(Document document, String id) {
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (element.getAttributeNS(ANDROID_NS, "id").endsWith("/" + id)) {
                return element;
            }
        }
        return null;
    }

    private static Element findById(Element scope, String id) {
        NodeList elements = scope.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (element.getAttributeNS(ANDROID_NS, "id").endsWith("/" + id)) {
                return element;
            }
        }
        return null;
    }

    private static String attr(Element element, String localName) {
        String value = element.getAttributeNS(ANDROID_NS, localName);
        if (value.isEmpty()) {
            value = element.getAttributeNS(APP_NS, localName);
        }
        if (value.isEmpty()) {
            value = element.getAttribute("android:" + localName);
        }
        if (value.isEmpty()) {
            value = element.getAttribute("app:" + localName);
        }
        return value;
    }
}
