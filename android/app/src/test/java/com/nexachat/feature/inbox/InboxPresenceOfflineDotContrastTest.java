package com.nexachat.feature.inbox;

import com.nexachat.res.ContrastUnitTest;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Offline presence dot fill must clear the 3:1 icon floor on all six inbox avatar fills per theme. */
public final class InboxPresenceOfflineDotContrastTest {

    private static final String[] LIGHT_CONTAINERS = {
            "avatar_indigo_container",
            "avatar_blue_container",
            "avatar_cyan_container",
            "avatar_teal_container",
            "avatar_emerald_container",
            "avatar_amber_container",
    };

    private static final String[] DARK_CONTAINERS = {
            "avatar_indigo_container_dark",
            "avatar_blue_container_dark",
            "avatar_cyan_container_dark",
            "avatar_teal_container_dark",
            "avatar_emerald_container_dark",
            "avatar_amber_container_dark",
    };

    private static final double ICON_MINIMUM = 3.0;

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
    public void offlineDotFill_meetsIconContrastOnSixLightAvatarFills() throws Exception {
        Map<String, String> colors = loadMergedColors(false);
        String offlineFill = resolveColor(colors, "colorOnSurfaceVariant");
        for (String container : LIGHT_CONTAINERS) {
            String bg = resolveColor(colors, container);
            double ratio = ContrastUnitTest.computeContrastRatio(offlineFill, bg);
            assertTrue(container + " needs >= " + ICON_MINIMUM + ":1 but was " + ratio,
                    ratio >= ICON_MINIMUM);
        }
    }

    @Test
    public void offlineDotFill_meetsIconContrastOnSixDarkAvatarFills() throws Exception {
        Map<String, String> colors = loadMergedColors(true);
        String offlineFill = resolveColor(colors, "colorOnSurfaceVariant");
        for (String container : DARK_CONTAINERS) {
            String bg = resolveColor(colors, container);
            double ratio = ContrastUnitTest.computeContrastRatio(offlineFill, bg);
            assertTrue(container + " needs >= " + ICON_MINIMUM + ":1 but was " + ratio,
                    ratio >= ICON_MINIMUM);
        }
    }

    @Test
    public void legacyOfflineDotToken_failsOnTealLightFill() throws Exception {
        Map<String, String> colors = loadMergedColors(false);
        String legacy = resolveColor(colors, "stitch_offline_dot");
        String bg = resolveColor(colors, "avatar_teal_container");
        double ratio = ContrastUnitTest.computeContrastRatio(legacy, bg);
        assertTrue("expected legacy offline dot below icon floor on teal fill", ratio < ICON_MINIMUM);
    }

    private Map<String, String> loadMergedColors(boolean night) throws Exception {
        Map<String, String> colors = loadColors(new File(resDir, "values/colors_nexachat.xml"));
        colors.putAll(loadColors(new File(resDir, "values/colors_avatar.xml")));
        if (night) {
            colors.putAll(loadColors(new File(resDir, "values-night/colors_nexachat.xml")));
        }
        return colors;
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
        return raw;
    }

    private static String resolveColor(Map<String, String> raw, String name) {
        String value = raw.get(name);
        assertNotNull(name + " was not defined", value);
        Set<String> visiting = new HashSet<>();
        while (value.startsWith("@color/")) {
            String ref = value.substring("@color/".length());
            if (!visiting.add(ref)) {
                throw new IllegalArgumentException("Circular color reference for " + name);
            }
            value = raw.get(ref);
            assertNotNull("Unresolved @" + ref + " for " + name, value);
        }
        return value;
    }
}
