package com.nexachat.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Poll create sheet contract: the form scrolls between the pinned header and
 * the pinned action row, every field shares one left/right edge, no button
 * carries local rounding, and the per-row remove slot collapses instead of
 * reserving width when removal is not allowed.
 */
public final class PollCreateSheetLayoutContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";
    private static final String SCROLL = "androidx.core.widget.NestedScrollView";
    private static final String BUTTON = "com.google.android.material.button.MaterialButton";
    private static final String TEXT_INPUT = "com.google.android.material.textfield.TextInputLayout";
    private static final Set<String> FLEX_WIDTHS = Set.of("match_parent", "wrap_content", "0dp");

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
    public void sheetBodyScrollsBetweenPinnedHeaderAndActions() throws Exception {
        Document doc = parse("layout/sheet_create_poll.xml");
        NodeList scrolls = doc.getElementsByTagName(SCROLL);
        assertEquals("sheet must keep exactly one scrolling body", 1, scrolls.getLength());
        Element scroll = (Element) scrolls.item(0);
        assertTrue("question field must live in the scrolling body",
                isDescendant(find(doc, "pollQuestionInput"), scroll));
        assertTrue("option rows must live in the scrolling body",
                isDescendant(find(doc, "pollOptionInputs"), scroll));
        assertFalse("primary action must not scroll away",
                isDescendant(find(doc, "pollCreateSubmit"), scroll));
        assertFalse("cancel must not scroll away",
                isDescendant(find(doc, "pollCreateCancel"), scroll));
        assertEquals("scrolling body must shrink-wrap until it hits the chrome",
                "true", app(scroll, "layout_constrainedHeight"));
        assertEquals("0dp", android(scroll, "layout_height"));
        assertEquals("@id/pollCreateFooter",
                app(scroll, "layout_constraintBottom_toTopOf"));
    }

    @Test
    public void fieldsAndOptionRowsShareOneEdgeContract() throws Exception {
        Document sheet = parse("layout/sheet_create_poll.xml");
        NodeList inputs = sheet.getElementsByTagName(TEXT_INPUT);
        assertTrue("sheet must contain fields", inputs.getLength() > 0);
        for (int i = 0; i < inputs.getLength(); i++) {
            Element field = (Element) inputs.item(i);
            assertEquals("question field must fill the sheet column",
                    "match_parent", android(field, "layout_width"));
            assertFalse(field.hasAttributeNS(ANDROID, "layout_marginStart"));
            assertFalse(field.hasAttributeNS(ANDROID, "layout_marginEnd"));
        }
        Document row = parse("layout/item_poll_option_input.xml");
        Element optionLayout = find(row, "optionLayout");
        assertEquals("option field must fill the row's remaining width",
                "0dp", android(optionLayout, "layout_width"));
        assertEquals("1", android(optionLayout, "layout_weight"));
        assertFalse(optionLayout.hasAttributeNS(ANDROID, "layout_marginStart"));
        assertFalse(optionLayout.hasAttributeNS(ANDROID, "layout_marginEnd"));
        Element remove = find(row, "optionRemove");
        assertEquals("@dimen/min_touch_target", android(remove, "layout_width"));
        assertEquals("@dimen/min_touch_target", android(remove, "layout_height"));
    }

    @Test
    public void sheetButtonsCarryNoLocalRoundingOrFixedWidth() throws Exception {
        String[] layouts = {"layout/sheet_create_poll.xml", "layout/item_poll_option_input.xml"};
        for (String layout : layouts) {
            Document doc = parse(layout);
            NodeList buttons = doc.getElementsByTagName(BUTTON);
            for (int i = 0; i < buttons.getLength(); i++) {
                Element button = (Element) buttons.item(i);
                String id = android(button, "id");
                assertFalse(layout + " " + id + " must not set a local cornerRadius",
                        button.hasAttributeNS(APP, "cornerRadius")
                                || button.hasAttributeNS(ANDROID, "cornerRadius"));
                assertTrue(layout + " " + id + " must not use a fixed width",
                        FLEX_WIDTHS.contains(android(button, "layout_width")));
            }
        }
        Document sheet = parse("layout/sheet_create_poll.xml");
        Element submit = find(sheet, "pollCreateSubmit");
        assertEquals("primary action uses the shared filled button style",
                "@style/Widget.NexaChat.Button", submit.getAttribute("style"));
    }

    @Test
    public void removeSlotCollapsesWhenRemovalIsDisallowed() throws Exception {
        String source = readJava("com/nexachat/feature/poll/PollCreateSheet.java");
        assertFalse("a hidden remove slot must not keep reserving width",
                source.contains("View.INVISIBLE"));
        assertTrue(source.contains("View.GONE"));
    }

    private boolean isDescendant(Element node, Element ancestor) {
        Node parent = node.getParentNode();
        while (parent != null) {
            if (parent == ancestor) {
                return true;
            }
            parent = parent.getParentNode();
        }
        return false;
    }

    private Element find(Document doc, String id) {
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(android(node, "id"))) {
                return node;
            }
        }
        throw new AssertionError("Missing @+id/" + id);
    }

    private Document parse(String relative) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(resDir, relative));
    }

    private static String readJava(String relative) throws Exception {
        Path[] candidates = new Path[] {
                Path.of("src/main/java", relative),
                Path.of("app/src/main/java", relative),
                Path.of("android/app/src/main/java", relative)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing " + relative);
    }

    private static String android(Element element, String name) {
        String value = element.getAttributeNS(ANDROID, name);
        return value == null ? "" : value;
    }

    private static String app(Element element, String name) {
        String value = element.getAttributeNS(APP, name);
        return value == null ? "" : value;
    }
}
