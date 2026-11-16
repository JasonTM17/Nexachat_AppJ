package com.nexachat.feature.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Contract for the contacts letter rail: a touch-wide rail pinned to the
 * list's end edge that starts hidden, plus a transient drag bubble that stays
 * out of the accessibility tree.
 */
public final class ContactsLetterRailContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    @Test
    public void letterRail_isTouchWideEndAnchoredAndStartsHidden() throws Exception {
        Element rail = findById(parse("layout/fragment_contacts.xml"), "letterRail");
        assertEquals("com.nexachat.feature.contacts.ContactLetterRailView", rail.getTagName());
        assertEquals("@dimen/min_touch_target", attr(rail, "layout_width"));
        assertEquals("end", attr(rail, "layout_gravity"));
        assertEquals("gone", attr(rail, "visibility"));
    }

    @Test
    public void letterRail_staysInsideTheVisibleListViewport() throws Exception {
        Element rail = findById(parse("layout/fragment_contacts.xml"), "letterRail");
        String behavior = rail.getAttributeNS(APP, "layout_behavior");
        // The scrolling-view behavior measures a match_parent child taller
        // than the screen and pins its top to the app bar's bottom edge, so
        // the lowest letters ('#' included) render off-screen while the
        // header is expanded.
        assertNotEquals(
                "the rail is an overlay, not a scrolling child: it must not "
                        + "reuse the scrolling-view behavior",
                "@string/appbar_scrolling_view_behavior",
                behavior);
        assertEquals("com.nexachat.feature.contacts.ContactLetterRailBehavior", behavior);
        String source =
                source("java/com/nexachat/feature/contacts/ContactLetterRailBehavior.java");
        assertTrue("the rail's top must track the app bar's bottom edge",
                source.contains("appBar.getBottom()") || source.contains("dependency.getBottom()"));
        assertTrue("the rail's bottom must stay at the parent's bottom edge",
                source.contains("parent.getHeight()"));
    }

    @Test
    public void letterBubble_isTransientDecorationOutsideAccessibility() throws Exception {
        Element bubble = findById(parse("layout/fragment_contacts.xml"), "letterBubble");
        assertEquals("gone", attr(bubble, "visibility"));
        assertEquals("no", attr(bubble, "importantForAccessibility"));
        assertEquals("@drawable/bg_letter_bubble", attr(bubble, "background"));
        Element drawable = parse("drawable/bg_letter_bubble.xml").getDocumentElement();
        assertEquals("oval", drawable.getAttributeNS(ANDROID, "shape"));
    }

    @Test
    public void fragment_bindsRailToTheAdapterRows() throws Exception {
        String source = source("java/com/nexachat/feature/contacts/ContactsFragment.java");
        assertTrue("the rail listener must resolve letters to adapter positions",
                source.contains("ContactLetterIndex.adapterPosition"));
        assertTrue("the rail letters come from the adapter's current rows",
                source.contains("ContactLetterIndex.letters(adapter.getCurrentList())"));
        assertTrue("activating a letter scrolls the header to the top",
                source.contains("scrollToPositionWithOffset"));
    }

    private static Element findById(Document doc, String id) {
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if (e.getAttributeNS(ANDROID, "id").endsWith("/" + id)) {
                return e;
            }
        }
        throw new AssertionError("no element with id @" + id);
    }

    private static String attr(Element e, String localName) {
        return e.getAttributeNS(ANDROID, localName);
    }

    private static Document parse(String relative) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        File[] candidates = {
                new File("src/main/res/" + relative),
                new File("app/src/main/res/" + relative),
                new File("android/app/src/main/res/" + relative)
        };
        for (File f : candidates) {
            if (f.isFile()) {
                return factory.newDocumentBuilder().parse(f);
            }
        }
        throw new AssertionError("Could not locate res/" + relative);
    }

    private static String source(String relative) throws Exception {
        File[] candidates = {
                new File("src/main/" + relative),
                new File("app/src/main/" + relative),
                new File("android/app/src/main/" + relative)
        };
        for (File f : candidates) {
            if (f.isFile()) {
                return new String(Files.readAllBytes(f.toPath()));
            }
        }
        throw new AssertionError("Could not locate src/main/" + relative);
    }
}
