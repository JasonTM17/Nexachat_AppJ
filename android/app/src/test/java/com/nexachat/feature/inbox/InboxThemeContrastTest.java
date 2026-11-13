package com.nexachat.feature.inbox;

import com.nexachat.res.ContrastUnitTest;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Extra contrast pairs for the extended shared theme surface and inbox accents. */
public final class InboxThemeContrastTest {

    private File resDir;

    @Before
    public void setUp() {
        File[] candidates = {
            new File("src/main/res"),
            new File("app/src/main/res"),
            new File("android/app/src/main/res"),
            new File("../app/src/main/res")
        };
        for (File candidate : candidates) {
            if (new File(candidate, "values/colors_nexachat.xml").isFile()) {
                resDir = candidate;
                return;
            }
        }
        throw new IllegalStateException("Could not locate Android resources");
    }

    @Test
    public void presenceDotMeetsContrastOnSurfaceRing() throws Exception {
        Map<String, String> light = loadThemeColors(false);
        Map<String, String> dark = loadThemeColors(true);
        assertPair(light, "stitch_online_dot", "colorSurface", 3.0);
        assertPair(dark, "stitch_online_dot", "colorSurface", 3.0);
        assertPair(light, "stitch_online_dot", "colorSurfaceContainer", 3.0);
        assertPair(dark, "stitch_online_dot", "colorSurfaceContainer", 3.0);
    }

    @Test
    public void lightSurfaceAndAccentPairsMeetContrastTargets() throws Exception {
        Map<String, String> colors = loadThemeColors(false);
        assertPair(colors, "colorOnSurface", "colorSurfaceContainer", 4.5);
        assertPair(colors, "colorOnSurface", "colorSurfaceContainerHigh", 4.5);
        assertPair(colors, "colorOnSurface", "colorSurfaceContainerHighest", 4.5);
        assertPair(colors, "colorOnSurfaceVariant", "colorSurfaceContainerHigh", 4.5);
        assertPair(colors, "colorPrimary", "colorSurface", 4.5);
        assertPair(colors, "colorTertiary", "colorSurface", 3.0);
        assertPair(colors, "colorOnPrimary", "bubble_mine", 4.5);
        assertPair(colors, "colorOnSurface", "bubble_other", 4.5);
    }

    @Test
    public void darkSurfaceAndAccentPairsMeetContrastTargets() throws Exception {
        Map<String, String> colors = loadThemeColors(true);
        assertPair(colors, "colorOnSurface", "colorSurfaceContainer", 4.5);
        assertPair(colors, "colorOnSurface", "colorSurfaceContainerHigh", 4.5);
        assertPair(colors, "colorOnSurface", "colorSurfaceContainerHighest", 4.5);
        assertPair(colors, "colorOnSurfaceVariant", "colorSurfaceContainerHigh", 4.5);
        assertPair(colors, "colorPrimary", "colorSurface", 4.5);
        assertPair(colors, "colorTertiary", "colorSurface", 3.0);
        assertPair(colors, "colorOnPrimaryContainer", "bubble_mine", 4.5);
        assertPair(colors, "colorOnSurface", "bubble_other", 4.5);
    }

    /**
     * Checked inbox filter chips use the shared brand fill and on-fill roles in
     * both themes; the night primary remains an accent for text, icons and strokes.
     */
    @Test
    public void checkedFilterChipTextOnPrimaryMeetsContrast() throws Exception {
        Document styles = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File(resDir, "values/styles_nexachat.xml"));
        Element filterStyle = null;
        NodeList styleNodes = styles.getElementsByTagName("style");
        for (int i = 0; i < styleNodes.getLength(); i++) {
            Element style = (Element) styleNodes.item(i);
            if ("Widget.NexaChat.Chip.Filter".equals(style.getAttribute("name"))) {
                filterStyle = style;
                break;
            }
        }
        assertNotNull("Widget.NexaChat.Chip.Filter", filterStyle);
        String textName = selectorColor(
                filterStyle, "android:textColor", true, false);
        String pressedFillName = selectorColor(
                filterStyle, "chipBackgroundColor", true, true);
        Map<String, String> light = loadThemeColors(false);
        Map<String, String> dark = loadThemeColors(true);
        assertPair(dark, textName, "brand_fill", 4.5);
        assertPair(light, textName, "brand_fill", 4.5);
        assertEquals("checked filter label must use the on-fill token",
                "brand_on_fill", textName);
        assertPair(light, textName, pressedFillName, 4.5);
        assertPair(dark, textName, pressedFillName, 4.5);
    }

    private static Map<String, String> loadColors(File file) throws Exception {
        Map<String, String> raw = new HashMap<>();
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(file);
        NodeList nodes = document.getDocumentElement().getElementsByTagName("color");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            raw.put(element.getAttribute("name"), element.getTextContent().trim());
        }
        Map<String, String> resolved = new HashMap<>();
        for (String name : raw.keySet()) {
            String value = raw.get(name);
            while (value.startsWith("@color/")) {
                value = raw.get(value.substring("@color/".length()));
                if (value == null) {
                    throw new IllegalArgumentException("Unresolvable color reference in " + file);
                }
            }
            resolved.put(name, value);
        }
        return resolved;
    }

    private Map<String, String> loadThemeColors(boolean night) throws Exception {
        Map<String, String> colors = loadColors(new File(resDir, "values/colors_nexachat.xml"));
        colors.putAll(loadColors(new File(resDir, "values/colors_brand_fill.xml")));
        if (night) {
            colors.putAll(loadColors(new File(resDir, "values-night/colors_nexachat.xml")));
        }
        return colors;
    }

    private String selectorColor(Element style, String itemName, boolean checked, boolean pressed)
            throws Exception {
        String reference = null;
        NodeList items = style.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if (itemName.equals(item.getAttribute("name"))) {
                reference = item.getTextContent().trim();
                break;
            }
        }
        assertNotNull(itemName, reference);
        assertTrue(itemName + " must be a color resource, was " + reference,
                reference.startsWith("@color/"));
        File selector = new File(resDir, "color/" + reference.substring("@color/".length()) + ".xml");
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(selector);
        NodeList states = document.getElementsByTagName("item");
        Element match = null;
        for (int i = 0; i < states.getLength(); i++) {
            Element state = (Element) states.item(i);
            if (stateMatches(state, "android:state_enabled", true)
                    && stateMatches(state, "android:state_checked", checked)
                    && stateMatches(state, "android:state_pressed", pressed)) {
                match = state;
                break;
            }
        }
        assertNotNull(reference + " missing checked=" + checked + " pressed=" + pressed, match);
        String color = match.getAttribute("android:color");
        assertTrue(color, color.startsWith("@color/"));
        return color.substring("@color/".length());
    }

    private static boolean stateMatches(Element item, String attribute, boolean actual) {
        if (!item.hasAttribute(attribute)) {
            return true;
        }
        return Boolean.parseBoolean(item.getAttribute(attribute)) == actual;
    }

    private static void assertPair(
            Map<String, String> colors,
            String foreground,
            String background,
            double minimum) {
        String fg = colors.get(foreground);
        String bg = colors.get(background);
        assertNotNull(foreground + " was not defined", fg);
        assertNotNull(background + " was not defined", bg);
        double contrast = ContrastUnitTest.computeContrastRatio(fg, bg);
        assertTrue(foreground + " on " + background + " expected >= "
                        + minimum + ":1 but was " + contrast,
                contrast >= minimum);
    }
}
