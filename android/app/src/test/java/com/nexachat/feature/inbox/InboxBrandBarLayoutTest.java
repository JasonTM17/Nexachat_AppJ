package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

/** Brand chrome stays on screen; the compose FAB must not cover the last row. */
public final class InboxBrandBarLayoutTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    @Test
    public void brandToolbarDoesNotScrollAway() throws Exception {
        Element toolbar = requireId("fragment_inbox.xml", "toolbar");
        assertEquals(
                "the NexaChat title bar must stay pinned like WhatsApp/Telegram",
                "",
                toolbar.getAttributeNS(APP, "layout_scrollFlags"));
        assertEquals("@string/app_name", requireId("fragment_inbox.xml", "inboxBrandTitle")
                .getAttributeNS(ANDROID, "text"));
    }

    @Test
    public void listViewportClearsTheComposeFab() throws Exception {
        Element viewport = requireId("fragment_inbox.xml", "inboxListViewport");
        assertEquals("@dimen/fab_clearance", viewport.getAttributeNS(ANDROID, "paddingBottom"));
        assertEquals("true", viewport.getAttributeNS(ANDROID, "clipToPadding"));
        Element list = requireId("fragment_inbox.xml", "conversationList");
        String listPadding = list.getAttributeNS(ANDROID, "paddingBottom");
        assertTrue(
                "list padding must not steal extra space under the already-padded viewport",
                listPadding.isEmpty() || "0dp".equals(listPadding));
    }

    private static Element requireId(String file, String id) throws Exception {
        Document document = parse("layout/" + file);
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) {
                return node;
            }
        }
        throw new AssertionError("Missing " + id + " in " + file);
    }

    private static Document parse(String relative) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(Path.of("src/main/res", relative).toFile());
    }
}
