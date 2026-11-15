package com.nexachat.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Sheet chrome contract: drag handles are rounded pills on the
 * surface-container-highest step, and sheet surfaces/cards carry a tonal fill
 * with no stroke and no elevation.
 */
public final class SheetChromeLayoutContractTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String NS_APP = "http://schemas.android.com/apk/res-auto";
    private static final String CARD = "com.google.android.material.card.MaterialCardView";

    private File resDir;

    @Before
    public void setUp() {
        File[] candidates = new File[] {
                new File("src/main/res"),
                new File("app/src/main/res"),
                new File("android/app/src/main/res"),
                new File("../app/src/main/res")
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory() && new File(candidate, "values/colors_nexachat.xml").isFile()) {
                resDir = candidate;
                return;
            }
        }
        throw new IllegalStateException("Could not locate res directory");
    }

    @Test
    public void sheetHandlesArePillsOnTheContainerHighestStep() throws Exception {
        String[] layouts = {
                "layout/bottom_sheet_summary.xml",
                "layout/bottom_sheet_ask_chat.xml",
                "layout/bottom_sheet_attachment_picker.xml",
                "layout/bottom_sheet_group_add_member.xml",
                "layout/sheet_attachment_picker.xml",
                "layout/sheet_create_poll.xml",
                "layout/sheet_reminder_editor.xml",
                "layout/dialog_forward.xml"
        };
        for (String relative : layouts) {
            Element handle = findHandle(parse(new File(resDir, relative)));
            assertTrue(relative + " is missing a drag handle card", handle != null);
            assertEquals(relative + " handle fill",
                    "?attr/colorSurfaceContainerHighest", app(handle, "cardBackgroundColor"));
            assertEquals(relative + " handle radius",
                    "@dimen/corner_full", app(handle, "cardCornerRadius"));
            assertEquals(relative + " handle elevation", "0dp", app(handle, "cardElevation"));
            assertEquals(relative + " handle stroke", "0dp", app(handle, "strokeWidth"));
        }
    }

    @Test
    public void sheetSurfacesAndCardsStayTonalAndStrokeless() throws Exception {
        assertTonalStrokelessCard("layout/bottom_sheet_attachment_picker.xml", "attachmentSheetRoot", true);
        assertTonalStrokelessCard("layout/bottom_sheet_group_add_member.xml", "addMemberSheetRoot", true);
        assertTonalStrokelessCard("layout/sheet_attachment_picker.xml", null, true);
        assertTonalStrokelessCard("layout/dialog_forward.xml", "forwardDialogRoot", true);
        assertTonalStrokelessCard("layout/bottom_sheet_ask_chat.xml", "askAnswerCard", false);
        assertTonalStrokelessCard("layout/item_archive_row.xml", "archiveRowCard", false);
        assertTonalStrokelessCard("layout/item_archive_media.xml", "archiveMediaCard", false);
    }

    @Test
    public void sheetSurfacesUseTheLowestContainerStep() throws Exception {
        Element summary = parse(new File(resDir, "layout/bottom_sheet_summary.xml")).getDocumentElement();
        assertEquals("?attr/colorSurfaceContainerLowest", android(summary, "background"));

        Document askSheet = parse(new File(resDir, "drawable/bg_ask_sheet.xml"));
        Element solid = (Element) askSheet.getElementsByTagName("solid").item(0);
        assertEquals("?attr/colorSurfaceContainerLowest", android(solid, "color"));

        assertEquals("?attr/colorSurfaceContainerLowest",
                app(card("layout/bottom_sheet_attachment_picker.xml", "attachmentSheetRoot"), "cardBackgroundColor"));
        assertEquals("?attr/colorSurfaceContainerLowest",
                app(card("layout/bottom_sheet_group_add_member.xml", "addMemberSheetRoot"), "cardBackgroundColor"));
        assertEquals("?attr/colorSurfaceContainerLowest",
                app(card("layout/sheet_attachment_picker.xml", null), "cardBackgroundColor"));
        assertEquals("?attr/colorSurfaceContainerLowest",
                app(card("layout/dialog_forward.xml", "forwardDialogRoot"), "cardBackgroundColor"));
        assertEquals("?attr/colorSurfaceContainerLowest",
                app(card("layout/item_archive_row.xml", "archiveRowCard"), "cardBackgroundColor"));
        assertEquals("?attr/colorSurfaceContainerLowest",
                app(card("layout/item_archive_media.xml", "archiveMediaCard"), "cardBackgroundColor"));
    }

    private void assertTonalStrokelessCard(String relative, String id, boolean rootCard) throws Exception {
        Element card = card(relative, id);
        assertEquals(relative + " stroke", "0dp", app(card, "strokeWidth"));
        assertEquals(relative + " elevation", "0dp", app(card, "cardElevation"));
        String fill = app(card, "cardBackgroundColor");
        assertTrue(relative + " fill is not a tonal container role: " + fill,
                fill.contains("Container"));
    }

    private Element card(String relative, String id) throws Exception {
        Document doc = parse(new File(resDir, relative));
        if (id == null) {
            return doc.getDocumentElement();
        }
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(android(node, "id"))) {
                return node;
            }
        }
        throw new AssertionError("Missing @" + id + " in " + relative);
    }

    /** The drag handle is the only card in a sheet marked not important for accessibility. */
    private static Element findHandle(Document doc) {
        NodeList nodes = doc.getElementsByTagName(CARD);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if ("no".equals(android(node, "importantForAccessibility"))) {
                return node;
            }
        }
        return null;
    }

    private static String android(Element element, String name) {
        return element.getAttributeNS(NS_ANDROID, name);
    }

    private static String app(Element element, String name) {
        return element.getAttributeNS(NS_APP, name);
    }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }
}
