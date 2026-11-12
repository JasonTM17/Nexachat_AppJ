package com.nexachat.feature.profile;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Layout contract for the profile sign-in methods card. */
public final class SignInMethodsLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void signInRowsMeetTouchTargetAndAccessibility() throws Exception {
        Element google = requireId("fragment_profile.xml", "googleAccountRow");
        Element phone = requireId("fragment_profile.xml", "signInMethodPhoneRow");
        Element error = requireId("fragment_profile.xml", "signInMethodsError");

        assertMinTouchTarget(google);
        assertMinTouchTarget(phone);
        assertEquals("gone", error.getAttributeNS(ANDROID, "visibility").toLowerCase());
        assertEquals("polite", error.getAttributeNS(ANDROID, "accessibilityLiveRegion"));
    }

    @Test
    public void profileLayoutDoesNotShipLinkedAccountsCaption() throws Exception {
        Document document = parse("layout/fragment_profile.xml");
        NodeList textViews = document.getElementsByTagName("TextView");
        for (int i = 0; i < textViews.getLength(); i++) {
            Element node = (Element) textViews.item(i);
            String text = node.getAttributeNS(ANDROID, "text");
            assertFalse(text.toUpperCase().contains("CÁC TÀI KHOẢN"));
            assertFalse(text.toUpperCase().contains("LINKED ACCOUNTS"));
        }
    }

    private static void assertMinTouchTarget(Element node) {
        String minHeight = node.getAttributeNS(ANDROID, "minHeight");
        assertTrue(minHeight.contains("min_touch_target") || minHeight.endsWith("48dp"));
    }

    private static Element requireId(String file, String id) throws Exception {
        Element element = view(file, id);
        assertNotNull(element);
        return element;
    }

    private static Element view(String file, String id) throws Exception {
        Document document = parse("layout/" + file);
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) {
                return node;
            }
        }
        throw new AssertionError("Missing " + id + " in " + file);
    }

    private static Document parse(String relative) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(Path.of("src/main/res", relative).toFile());
    }
}
