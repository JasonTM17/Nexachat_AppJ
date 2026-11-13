package com.nexachat.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Layout contract for message action sheet rows at large font scale. */
public final class MessageActionRowLayoutContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void actionLabelWrapsWithoutLineCap() throws Exception {
        Element label = findById("item_message_action_row.xml", "actionLabel");
        assertEquals("wrap_content", label.getAttributeNS(ANDROID, "layout_height"));
        assertTrue(
                "actionLabel must not cap lines for long localized labels",
                label.getAttributeNS(ANDROID, "maxLines").isEmpty());
        assertTrue(
                "actionLabel must not ellipsize when wrapping",
                label.getAttributeNS(ANDROID, "ellipsize").isEmpty());
    }

    @Test
    public void rowKeepsMinimumTouchHeightAndGrowsWithLabel() throws Exception {
        Element root = loadRoot("item_message_action_row.xml");
        assertEquals("wrap_content", root.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("@dimen/min_touch_target", root.getAttributeNS(ANDROID, "minHeight"));
        Element label = findById("item_message_action_row.xml", "actionLabel");
        assertEquals("0dp", label.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("1", label.getAttributeNS(ANDROID, "layout_weight"));
    }

    @Test
    public void iconTileStaysTopAlignedWithFirstLabelLine() throws Exception {
        Element root = loadRoot("item_message_action_row.xml");
        String gravity = root.getAttributeNS(ANDROID, "gravity");
        assertTrue(
                "Row should top-align the icon tile with the first label line when text wraps",
                "top".equals(gravity) || gravity.contains("top"));
    }

    private static Element loadRoot(String layout) throws Exception {
        File root = layoutRoot();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(root, layout)).getDocumentElement();
    }

    private static Element findById(String layout, String id) throws Exception {
        File root = layoutRoot();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder().parse(new File(root, layout)).getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (("@+id/" + id).equals(el.getAttributeNS(ANDROID, "id"))) {
                return el;
            }
        }
        throw new AssertionError("Missing " + id);
    }

    private static File layoutRoot() {
        File[] candidates = new File[] {
                new File("src/main/res/layout"),
                new File("app/src/main/res/layout"),
                new File("android/app/src/main/res/layout"),
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate layout directory");
    }
}
