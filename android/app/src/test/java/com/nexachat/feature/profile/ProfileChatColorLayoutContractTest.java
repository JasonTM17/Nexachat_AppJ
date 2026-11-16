package com.nexachat.feature.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Layout contract for the Profile chat-color picker: a label-only settings row
 * followed by eight square swatch cells, each with a named content description,
 * a 48dp touch target and a hidden selection check.
 */
public final class ProfileChatColorLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    private static final String[] CELLS = {
            "chatColorSwatchPlum", "chatColorSwatchIndigo", "chatColorSwatchBlue",
            "chatColorSwatchTeal", "chatColorSwatchGreen", "chatColorSwatchAmber",
            "chatColorSwatchRose", "chatColorSwatchSlate" };

    @Test
    public void chatColorRowSitsBelowThemeControls() throws Exception {
        Document doc = parseLayout();
        List<String> order = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            String id = ((Element) nodes.item(i)).getAttributeNS(ANDROID, "id");
            if (id.equals("@+id/themeQuickChips") || id.equals("@+id/chatColorRow")
                    || id.equals("@+id/chatColorSwatchArea")) {
                order.add(id);
            }
        }
        assertEquals(
                List.of("@+id/themeQuickChips", "@+id/chatColorRow", "@+id/chatColorSwatchArea"),
                order);
    }

    @Test
    public void rowCarriesNoHelperText() throws Exception {
        Element row = requireId("chatColorRow");
        // The row is a label: no subtitle, value or hint element may hang below it.
        assertEquals(0, row.getElementsByTagName("*").getLength());
    }

    @Test
    public void allEightSwatchCellsAreEqualWidthTargets() throws Exception {
        for (String cell : CELLS) {
            Element el = requireId(cell);
            // 4x2 grid: cells share the row width evenly, never wrap unevenly.
            assertEquals(cell + " width must stretch to an equal grid share", "0dp",
                    el.getAttributeNS(ANDROID, "layout_width"));
            assertEquals(cell + " needs a weight to share the row", "1",
                    el.getAttributeNS(ANDROID, "layout_weight"));
            assertEquals(cell + " height", "@dimen/min_touch_target",
                    el.getAttributeNS(ANDROID, "layout_height"));
            assertEquals(cell + " must be clickable", "true",
                    el.getAttributeNS(ANDROID, "clickable"));
            assertTrue(cell + " needs a color name description",
                    el.getAttributeNS(ANDROID, "contentDescription").startsWith("@string/chat_color_"));
        }
    }

    @Test
    public void swatchesTileTwoRowsOfFour() throws Exception {
        Document doc = parseLayout();
        Element area = null;
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if ("@+id/chatColorSwatchArea".equals(node.getAttributeNS(ANDROID, "id"))) {
                area = node;
            }
        }
        assertNotNull("chatColorSwatchArea", area);
        List<Element> rows = new ArrayList<>();
        NodeList children = area.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                rows.add(child);
            }
        }
        assertEquals("grid must be exactly two rows", 2, rows.size());
        for (int r = 0; r < rows.size(); r++) {
            List<Element> cells = new ArrayList<>();
            NodeList rowChildren = rows.get(r).getChildNodes();
            for (int i = 0; i < rowChildren.getLength(); i++) {
                if (rowChildren.item(i) instanceof Element child) {
                    cells.add(child);
                }
            }
            assertEquals("row " + r + " must hold four cells", 4, cells.size());
            for (int c = 0; c < cells.size(); c++) {
                String expected = "@+id/" + CELLS[r * 4 + c];
                assertEquals("row " + r + " cell " + c,
                        expected, cells.get(c).getAttributeNS(ANDROID, "id"));
            }
        }
    }

    @Test
    public void everyCellPaintsASquareFillAndHiddenCheck() throws Exception {
        for (String cell : CELLS) {
            Element el = requireId(cell);
            // Cells hold two unnamed children in fixed order: fill, then check.
            NodeList children = el.getElementsByTagName("ImageView");
            assertEquals(cell + " must contain fill and check", 2, children.getLength());
            Element fill = (Element) children.item(0);
            assertEquals(cell + " fill must draw the square swatch",
                    "@drawable/bg_chat_color_swatch",
                    fill.getAttributeNS(ANDROID, "background"));
            Element check = (Element) children.item(1);
            assertEquals("@drawable/ic_check", check.getAttributeNS(ANDROID, "src"));
            assertEquals("gone", check.getAttributeNS(ANDROID, "visibility"));
        }
    }

    @Test
    public void swatchDrawableStaysSquare() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder()
                .parse(Path.of("src/main/res/drawable/bg_chat_color_swatch.xml").toFile());
        Element shape = doc.getDocumentElement();
        assertEquals("rectangle", shape.getAttributeNS(ANDROID, "shape"));
        NodeList corners = shape.getElementsByTagName("corners");
        assertTrue("swatch needs a corners element", corners.getLength() == 1);
        String radius = ((Element) corners.item(0)).getAttributeNS(ANDROID, "radius");
        // Sharp square only: never a circle, pill, or a large rounded token.
        assertTrue("swatch corners must be 0dp or the documented corner_sm",
                radius.equals("0dp") || radius.equals("@dimen/corner_sm"));
    }

    private static Element requireId(String id) throws Exception {
        Document doc = parseLayout();
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) {
                return node;
            }
        }
        fail("Missing " + id + " in fragment_profile.xml");
        return null;
    }

    private static Document parseLayout() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(Path.of("src/main/res/layout/fragment_profile.xml").toFile());
    }
}
