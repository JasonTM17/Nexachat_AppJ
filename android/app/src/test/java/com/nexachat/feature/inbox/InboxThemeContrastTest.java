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
    public void lightSurfaceAndAccentPairsMeetContrastTargets() throws Exception {
        Map<String, String> colors = loadColors(new File(resDir, "values/colors_nexachat.xml"));
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
        Map<String, String> colors = loadColors(
                new File(resDir, "values-night/colors_nexachat.xml"));
        assertPair(colors, "colorOnSurface", "colorSurfaceContainer", 4.5);
        assertPair(colors, "colorOnSurface", "colorSurfaceContainerHigh", 4.5);
        assertPair(colors, "colorOnSurface", "colorSurfaceContainerHighest", 4.5);
        assertPair(colors, "colorOnSurfaceVariant", "colorSurfaceContainerHigh", 4.5);
        assertPair(colors, "colorPrimary", "colorSurface", 4.5);
        assertPair(colors, "colorTertiary", "colorSurface", 3.0);
        assertPair(colors, "colorOnPrimaryContainer", "bubble_mine", 4.5);
        assertPair(colors, "colorOnSurface", "bubble_other", 4.5);
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
