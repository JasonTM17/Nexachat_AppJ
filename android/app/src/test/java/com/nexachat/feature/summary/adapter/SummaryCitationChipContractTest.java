package com.nexachat.feature.summary.adapter;

import static org.junit.Assert.assertTrue;

import com.nexachat.res.ContrastUnitTest;
import java.io.File;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** WCAG contrast and touch target for jumpable summary citation chips. */
public final class SummaryCitationChipContractTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String NS_APP = "http://schemas.android.com/apk/res-auto";
    private static final double MIN_CONTRAST = 4.5;

    private File resDir;

    @Before
    public void setUp() {
        resDir = locateResDir();
    }

    @Test
    public void jumpableCitationChipMeetsDarkContrastFromTokens() throws Exception {
        Map<String, String> dark = loadColors(new File(resDir, "values-night/colors_nexachat.xml"));
        double ratio = ContrastUnitTest.computeContrastRatio(
                dark.get("brand_on_indigo_container"),
                dark.get("brand_indigo_container"));
        assertTrue("dark jumpable citation contrast=" + ratio, ratio >= MIN_CONTRAST);
    }

    @Test
    public void jumpableCitationChipMeetsLightContrastFromTokens() throws Exception {
        Map<String, String> light = loadColors(new File(resDir, "values/colors_nexachat.xml"));
        double ratio = ContrastUnitTest.computeContrastRatio(
                light.get("brand_on_indigo_container"),
                light.get("brand_indigo_container"));
        assertTrue("light jumpable citation contrast=" + ratio, ratio >= MIN_CONTRAST);
    }

    @Test
    public void staticCitationChipMeetsDarkContrastFromTokens() throws Exception {
        Map<String, String> dark = loadColors(new File(resDir, "values-night/colors_nexachat.xml"));
        double ratio = ContrastUnitTest.computeContrastRatio(
                dark.get("colorOnSurfaceVariant"),
                dark.get("bubble_other"));
        assertTrue("dark static citation contrast=" + ratio, ratio >= MIN_CONTRAST);
    }

    @Test
    public void staticCitationChipMeetsLightContrastFromTokens() throws Exception {
        Map<String, String> light = loadColors(new File(resDir, "values/colors_nexachat.xml"));
        double ratio = ContrastUnitTest.computeContrastRatio(
                light.get("colorOnSurfaceVariant"),
                light.get("bubble_other"));
        assertTrue("light static citation contrast=" + ratio, ratio >= MIN_CONTRAST);
    }

    @Test
    public void citationChipLayoutUses48dpMinHeight() throws Exception {
        Element chip = citationChipElement();
        String minHeight = chip.getAttributeNS(NS_APP, "chipMinHeight");
        assertTrue("expected @dimen/min_touch_target, was " + minHeight,
                "@dimen/min_touch_target".equals(minHeight));
    }

    private Element citationChipElement() throws Exception {
        File layout = new File(resDir, "layout/item_summary_citation.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(layout);
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if ("@+id/citationChip".equals(el.getAttributeNS(NS_ANDROID, "id"))) {
                return el;
            }
        }
        throw new AssertionError("citationChip missing");
    }

    private static Map<String, String> loadColors(File file) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        NodeList nodes = doc.getElementsByTagName("color");
        java.util.HashMap<String, String> raw = new java.util.HashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            raw.put(el.getAttribute("name"), el.getTextContent().trim());
        }
        java.util.HashMap<String, String> resolved = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            resolved.put(entry.getKey(), resolveColor(raw, entry.getValue()));
        }
        return resolved;
    }

    private static String resolveColor(Map<String, String> raw, String value) {
        if (value.startsWith("@color/")) {
            String ref = value.substring("@color/".length());
            return resolveColor(raw, raw.getOrDefault(ref, value));
        }
        return value;
    }

    private static File locateResDir() {
        File[] candidates = new File[] {
                new File("src/main/res"),
                new File("app/src/main/res"),
                new File("android/app/src/main/res"),
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory() && new File(candidate, "values/colors_nexachat.xml").isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("res dir not found");
    }
}
