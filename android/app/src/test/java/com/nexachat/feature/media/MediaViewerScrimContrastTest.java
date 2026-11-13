package com.nexachat.feature.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.nexachat.res.ContrastUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** The viewer back control must stay readable when the photo behind it is white. */
public final class MediaViewerScrimContrastTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void topScrimKeepsLightIconsAboveFourPointFiveOnAWhitePhoto() throws Exception {
        Element scrim = view("layout/fragment_media_viewer.xml", "viewerTopScrim");
        assertEquals("no", scrim.getAttributeNS(ANDROID, "importantForAccessibility"));
        assertEquals("@drawable/bg_media_viewer_top_scrim",
                scrim.getAttributeNS(ANDROID, "background"));
        String start = gradientStartColor();
        String icon = color("nexa_on_media_meta");
        String overWhite = ContrastUnitTest.compositeOver(start, "#FFFFFFFF");
        double ratio = ContrastUnitTest.computeContrastRatio(icon, overWhite);
        assertTrue("contrast " + ratio + " for " + icon + " on " + overWhite, ratio >= 4.5d);
    }

    private static String gradientStartColor() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Element root = factory.newDocumentBuilder()
                .parse(locate("drawable/bg_media_viewer_top_scrim.xml").toFile())
                .getDocumentElement();
        NodeList gradients = root.getElementsByTagName("gradient");
        assertTrue(gradients.getLength() == 1);
        String token = ((Element) gradients.item(0)).getAttributeNS(ANDROID, "startColor");
        assertTrue(token.startsWith("@color/"));
        return color(token.substring("@color/".length()));
    }

    private static String color(String name) throws Exception {
        String xml = new String(
                Files.readAllBytes(locate("values/colors_nexachat.xml")), StandardCharsets.UTF_8);
        String token = "name=\"" + name + "\">";
        int start = xml.indexOf(token);
        assertTrue(name + " missing", start > 0);
        start += token.length();
        int end = xml.indexOf("</color>", start);
        return xml.substring(start, end).trim();
    }

    private static Element view(String file, String id) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder()
                .parse(locate(file).toFile())
                .getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) {
                return node;
            }
        }
        throw new AssertionError("Missing " + id);
    }

    private static Path locate(String relative) {
        Path[] candidates = new Path[] {
                Path.of("src/main/res", relative),
                Path.of("app/src/main/res", relative),
                Path.of("android/app/src/main/res", relative)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Missing " + relative);
    }
}
