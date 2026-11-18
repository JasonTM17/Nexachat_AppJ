package com.nexachat.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class EncryptionLayoutAccessibilityUnitTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

    private File resDir;

    @Before
    public void setUp() {
        File[] candidates =
                new File[] {
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
    public void statusAndHintTextsUsePoliteLiveRegions() throws Exception {
        Document doc = parseLayout("fragment_encryption.xml");
        for (String id : new String[] {"e2eeStatus", "e2eeDirectoryHint"}) {
            Element el = findElementById(doc.getDocumentElement(), id);
            assertNotNull("fragment_encryption.xml must contain @" + id, el);
            assertEquals(
                    "@" + id + " must declare android:accessibilityLiveRegion=\"polite\"",
                    "polite",
                    getAttr(el, "accessibilityLiveRegion"));
        }
    }

    @Test
    public void theEncryptionScreenHasALoadingAffordance() throws Exception {
        Element el = findElementById(
                parseLayout("fragment_encryption.xml").getDocumentElement(), "e2eeLoading");
        assertNotNull("fragment_encryption.xml must contain @e2eeLoading", el);
        // The shared loading label, not the voice-player status string: the indicator
        // reports a screen-level wait, and every other progress indicator names it the same way.
        assertEquals("@string/loading_status", getAttr(el, "contentDescription"));
        assertEquals("e2eeLoading must use the Material indicator",
                "com.google.android.material.progressindicator.CircularProgressIndicator",
                el.getTagName());
    }

    private Document parseLayout(String name) throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File(resDir, "layout/" + name));
    }

    private static Element findElementById(Node node, String idSuffix) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) node;
            String id = el.getAttributeNS(NS_ANDROID, "id");
            if (id == null || id.isEmpty()) {
                id = el.getAttribute("android:id");
            }
            if (("@+id/" + idSuffix).equals(id) || id != null && id.endsWith("/" + idSuffix)) {
                return el;
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Element match = findElementById(children.item(i), idSuffix);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static String getAttr(Element el, String localName) {
        String val = el.getAttributeNS(NS_ANDROID, localName);
        if (val == null || val.isEmpty()) {
            val = el.getAttribute("android:" + localName);
        }
        return val == null ? "" : val;
    }
}
