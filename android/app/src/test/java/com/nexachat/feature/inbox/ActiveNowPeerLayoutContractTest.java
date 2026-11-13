package com.nexachat.feature.inbox;

import java.io.File;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class ActiveNowPeerLayoutContractTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test
    public void itemLayout_meetsActiveNowRailContract() throws Exception {
        File layout = locateResFile("layout/item_active_now_peer.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(layout);
        Element avatar = findElementByAndroidId(document.getDocumentElement(), "activePeerAvatar");
        Element root = document.getDocumentElement();
        assertNotNull(avatar);
        assertEquals("56dp", androidAttr(avatar, "layout_width"));
        assertEquals("56dp", androidAttr(avatar, "layout_height"));
        assertEquals("@dimen/min_touch_target", androidAttr(root, "minWidth"));
        assertEquals("@dimen/min_touch_target", androidAttr(root, "minHeight"));
    }

    private static String androidAttr(Element element, String name) {
        String value = element.getAttributeNS(ANDROID_NS, name);
        if (value.isEmpty()) {
            value = element.getAttribute("android:" + name);
        }
        return value;
    }

    private static Element findElementByAndroidId(Element root, String id) {
        String rawId = root.getAttributeNS(ANDROID_NS, "id");
        if (rawId.isEmpty()) {
            rawId = root.getAttribute("android:id");
        }
        if (id.equals(stripId(rawId))) {
            return root;
        }
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                Element found = findElementByAndroidId(element, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String stripId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String value = raw;
        if (value.startsWith("@+id/") || value.startsWith("@id/")) {
            value = value.substring(value.indexOf('/') + 1);
        }
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static File locateResFile(String relativePath) {
        File[] candidates = {
                new File("src/main/res/" + relativePath),
                new File("app/src/main/res/" + relativePath),
                new File("android/app/src/main/res/" + relativePath)
        };
        for (File file : candidates) {
            if (file.isFile()) {
                return file;
            }
        }
        throw new IllegalStateException("Missing " + relativePath);
    }
}
