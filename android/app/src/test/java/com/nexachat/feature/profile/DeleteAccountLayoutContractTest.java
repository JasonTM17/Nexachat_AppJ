package com.nexachat.feature.profile;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Layout contract for the Google-only deletion code flow. */
public final class DeleteAccountLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    @Test
    public void codeFlowViewsMeetTouchTargetAndAccessibility() throws Exception {
        Element sendCode = requireId("fragment_delete_account.xml", "sendDeletionCode");
        Element codeLayout = requireId("fragment_delete_account.xml", "deletionCodeLayout");
        Element codeInput = requireId("fragment_delete_account.xml", "deletionCodeInput");
        Element sentMessage = requireId("fragment_delete_account.xml", "deletionCodeSentMessage");

        assertMinTouchTarget(sendCode);
        assertEquals("gone", sendCode.getAttributeNS(ANDROID, "visibility").toLowerCase());
        assertEquals("gone", codeLayout.getAttributeNS(ANDROID, "visibility").toLowerCase());
        assertEquals("polite", sentMessage.getAttributeNS(ANDROID, "accessibilityLiveRegion"));
        assertEquals("false", codeInput.getAttributeNS(ANDROID, "saveEnabled"));
        assertEquals("@string/delete_account_code_label", codeLayout.getAttributeNS(ANDROID, "hint"));
    }

    @Test
    public void passwordLayoutContractUnchanged() throws Exception {
        Element layout = requireId("fragment_delete_account.xml", "passwordLayout");
        String endIcon = layout.getAttributeNS(APP, "endIconDrawable");
        assertEquals("@drawable/ic_visibility", endIcon);
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
