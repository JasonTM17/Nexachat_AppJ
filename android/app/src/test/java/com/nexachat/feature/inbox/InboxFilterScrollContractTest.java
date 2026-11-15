package com.nexachat.feature.inbox;

import static org.junit.Assert.assertTrue;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The filter chip row stays a horizontal carousel, so it must always hint that more
 * chips sit off the edge and keep "Archived" discoverable at large font scales.
 */
public final class InboxFilterScrollContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void chipScrollerHintsOffscreenContentWithAnEdgeFade() throws Exception {
        Element scroller = requireId("inboxFilterScroll");
        String fade = scroller.getAttributeNS(ANDROID, "requiresFadingEdge");
        assertTrue("chip scroller must fade horizontally, was " + fade,
                fade.contains("horizontal"));
        String length = scroller.getAttributeNS(ANDROID, "fadingEdgeLength");
        assertTrue("fadingEdgeLength must be a positive dimension, was " + length,
                length.endsWith("dp") && parseDimension(length) > 0);
    }

    private static int parseDimension(String length) {
        return Integer.parseInt(length.substring(0, length.length() - 2));
    }

    private static Element requireId(String id) throws Exception {
        File root = new File("src/main/res/layout");
        if (!root.isDirectory()) root = new File("app/src/main/res/layout");
        if (!root.isDirectory()) root = new File("android/app/src/main/res/layout");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder()
                .parse(new File(root, "fragment_inbox.xml")).getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) {
                return node;
            }
        }
        throw new AssertionError("Missing fragment_inbox.xml:" + id);
    }
}
