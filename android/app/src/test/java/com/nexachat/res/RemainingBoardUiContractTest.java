package com.nexachat.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class RemainingBoardUiContractTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String NS_APP = "http://schemas.android.com/apk/res-auto";

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
    public void boardFabsUseBrandFillRoles() throws Exception {
        assertFabBrand("fragment_poll.xml", "pollAdd");
        assertFabBrand("fragment_reminder.xml", "reminderAdd");
    }

    @Test
    public void boardButtonsUseNexaChatWidgetStyles() throws Exception {
        String[] layouts = {
                "layout/item_poll.xml",
                "layout/sheet_create_poll.xml",
                "layout/sheet_reminder_editor.xml"
        };
        for (String relative : layouts) {
            Document doc = parse(new File(resDir, relative));
            NodeList nodes = doc.getElementsByTagName("com.google.android.material.button.MaterialButton");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element button = (Element) nodes.item(i);
                String style = button.getAttributeNS(NS_ANDROID, "style");
                if (style.isEmpty()) {
                    style = button.getAttribute("style");
                }
                if (style.isEmpty()) {
                    continue;
                }
                assertTrue(relative + " must use NexaChat button styles: " + style,
                        style.contains("Widget.NexaChat.Button"));
            }
        }
    }

    private void assertFabBrand(String layoutName, String id) throws Exception {
        Element fab = findElementById(
                parse(new File(resDir, "layout/" + layoutName)).getDocumentElement(), id);
        assertNotNull(layoutName + " missing @" + id, fab);
        assertEquals("@color/brand_fill", attr(fab, "backgroundTint"));
        assertEquals("@color/brand_on_fill", attr(fab, "tint"));
    }

    private static Document parse(File file) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
    }

    private static String attr(Element element, String localName) {
        String value = element.getAttributeNS(NS_ANDROID, localName);
        if (value.isEmpty()) {
            value = element.getAttributeNS(NS_APP, localName);
        }
        if (value.isEmpty()) {
            value = element.getAttribute("android:" + localName);
        }
        if (value.isEmpty()) {
            value = element.getAttribute("app:" + localName);
        }
        return value;
    }

    private static Element findElementById(Node node, String idSuffix) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) node;
            String id = attr(el, "id");
            if (("@+id/" + idSuffix).equals(id) || id.endsWith("/" + idSuffix)) {
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
}
