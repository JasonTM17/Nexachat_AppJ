package com.nexachat.res;

import static org.junit.Assert.assertEquals;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class SettingsRowLayoutTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void settingsSubtitleAllowsGrowthAtLargeFont() throws Exception {
        Element subtitle = findById("view_settings_row.xml", "rowSubtitle");
        assertEquals("wrap_content", subtitle.getAttributeNS(ANDROID, "layout_height"));
        String maxLines = subtitle.getAttributeNS(ANDROID, "maxLines");
        if (!maxLines.isEmpty()) {
            assertEquals("maxLines must not force single-line clipping", "2", maxLines);
        }
    }

    private static Element findById(String layout, String id) throws Exception {
        File root = new File("android/app/src/main/res/layout");
        if (!root.isDirectory()) {
            root = new File("app/src/main/res/layout");
        }
        if (!root.isDirectory()) {
            root = new File("src/main/res/layout");
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder().parse(new File(root, layout)).getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (("@+id/" + id).equals(el.getAttributeNS(ANDROID, "id"))) {
                return el;
            }
        }
        throw new AssertionError("Missing " + id);
    }
}
