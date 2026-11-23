package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

/** The inbox header collapses on scroll; the compose FAB must not cover the last row. */
public final class InboxBrandBarLayoutTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    @Test
    public void everyAppBarChildScrollsWithTheHeader() throws Exception {
        // On short screens the toolbar + search pill + active-now + filter chips
        // leave the list only a few rows tall. An AppBarLayout child only
        // scrolls when every child above it also declares the scroll flag, so a
        // missing flag on toolbar or the search pill disables the whole header.
        Element appBar = requireId("fragment_inbox.xml", "appBar");
        NodeList children = appBar.getChildNodes();
        int scrollables = 0;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            scrollables++;
            String flags = ((Element) child).getAttributeNS(APP, "layout_scrollFlags");
            assertTrue(
                    "every direct AppBarLayout child must declare the scroll flag "
                            + "or the collapsing header stays pinned: "
                            + ((Element) child).getAttributeNS(ANDROID, "id"),
                    flags != null && flags.contains("scroll"));
        }
        assertTrue("the inbox header must have scrollable children", scrollables > 0);
        assertEquals("the app bar must lift when list content scrolls under it",
                "true", appBar.getAttributeNS(APP, "liftOnScroll"));
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
