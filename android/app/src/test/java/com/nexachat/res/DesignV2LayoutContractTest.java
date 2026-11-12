package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * XML contracts for v2 shared components. Negative fixtures are in-memory and must fail.
 */
public final class DesignV2LayoutContractTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

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
    public void composerFieldGrowsAndMediaMetaIsOpaque() throws Exception {
        Element composer = element(parse(new File(resDir, "layout/include_chat_composer_bar.xml")), "composerRoot");
        assertEquals("@dimen/composer_min_height", composer.getAttributeNS(NS_ANDROID, "minHeight"));
        Element input = element(composer.getOwnerDocument(), "messageInput");
        assertEquals("wrap_content", input.getAttributeNS(NS_ANDROID, "layout_height"));
        assertEquals("6", input.getAttributeNS(NS_ANDROID, "maxLines"));
        assertFalse(input.getAttributeNS(NS_ANDROID, "layout_height").endsWith("dp"));

        String meta = read(new File(resDir, "drawable/bg_message_image_meta.xml"));
        assertTrue(meta.contains("@color/nexa_media_meta_surface"));
        assertFalse(meta.contains("#"));

        Element quote = parse(new File(resDir, "layout/include_message_reply_quote.xml")).getDocumentElement();
        assertEquals("@dimen/min_touch_target", quote.getAttributeNS(NS_ANDROID, "minHeight"));
        Element separator = element(parse(new File(resDir, "layout/item_date_separator.xml")), "dateSeparatorLabel");
        assertEquals("wrap_content", separator.getAttributeNS(NS_ANDROID, "layout_height"));
    }

    @Test
    public void sharedLayoutsAvoidRawColorsAndFixedTextHeights() throws Exception {
        String[] layouts = {
                "layout/include_message_bubble_meta.xml",
                "layout/include_message_reply_quote.xml",
                "layout/include_message_reactions_row.xml",
                "layout/include_message_voice_player.xml",
                "layout/item_date_separator.xml",
                "layout/include_chat_composer_bar.xml",
                "layout/include_chat_reply_edit_banner.xml",
                "layout/sheet_attachment_picker.xml",
                "layout/view_empty_state.xml",
                "layout/view_error_state.xml",
                "layout/view_loading_state.xml"
        };
        for (String relative : layouts) {
            String body = read(new File(resDir, relative));
            assertFalse(relative + " has a raw color", body.contains("=\"#"));
            Document doc = parse(new File(resDir, relative));
            NodeList nodes = doc.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                String name = element.getTagName();
                if (!name.endsWith("TextView") && !name.equals("EditText") && !name.contains("TextInput")) {
                    continue;
                }
                String height = element.getAttributeNS(NS_ANDROID, "layout_height");
                assertFalse(relative + " fixes text height " + height, height.endsWith("dp"));
            }
        }
    }

    @Test
    public void negativeFixturesFailTheSharedRules() throws Exception {
        assertFalse(textHeightIsFlexible(parseString("""
                <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="40dp"
                    android:maxLines="2" />
                """)));
        assertFalse(clickableTargetIsValid(parseString("""
                <ImageButton xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="24dp"
                    android:layout_height="24dp" />
                """)));
        assertFalse(clickableTargetIsValid(parseString("""
                <com.google.android.material.chip.Chip xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:app="http://schemas.android.com/apk/res-auto"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Topic"
                    app:chipMinHeight="32dp" />
                """)));
        assertFalse(includeResolves(parseString("""
                <include layout="@layout/missing_design_v2_include" />
                """)));
    }

    private boolean textHeightIsFlexible(Document document) {
        String height = document.getDocumentElement().getAttributeNS(NS_ANDROID, "layout_height");
        return "wrap_content".equals(height) || "0dp".equals(height) || height.startsWith("@");
    }

    private boolean clickableTargetIsValid(Document document) {
        Element element = document.getDocumentElement();
        String description = element.getAttributeNS(NS_ANDROID, "contentDescription");
        String text = element.getAttributeNS(NS_ANDROID, "text");
        boolean labeled = !description.isEmpty() || !text.isEmpty();
        String height = element.getAttributeNS(NS_ANDROID, "layout_height");
        String minHeight = element.getAttributeNS(NS_ANDROID, "minHeight");
        if (minHeight.isEmpty()) {
            minHeight = element.getAttribute("app:chipMinHeight");
        }
        boolean tallEnough = "@dimen/min_touch_target".equals(height)
                || "@dimen/min_touch_target".equals(minHeight)
                || "48dp".equals(height)
                || "48dp".equals(minHeight);
        return labeled && tallEnough;
    }

    private boolean includeResolves(Document document) {
        String layout = document.getDocumentElement().getAttribute("layout");
        if (!layout.startsWith("@layout/")) {
            return false;
        }
        String name = layout.substring("@layout/".length());
        return new File(resDir, "layout/" + name + ".xml").isFile()
                || new File(resDir, "layout-land/" + name + ".xml").isFile();
    }

    private static Element element(Document document, String idSuffix) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (element.getAttributeNS(NS_ANDROID, "id").endsWith("/" + idSuffix)) {
                return element;
            }
        }
        throw new AssertionError("Missing @" + idSuffix);
    }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }

    private static Document parseString(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String read(File file) throws Exception {
        StringBuilder body = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
            }
        }
        return body.toString();
    }
}
