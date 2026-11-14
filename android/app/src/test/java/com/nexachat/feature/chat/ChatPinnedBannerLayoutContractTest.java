package com.nexachat.feature.chat;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class ChatPinnedBannerLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void pinnedTextWrapsAndIsHiddenFromAccessibilityTree() throws Exception {
        Document layout = parse("src/main/res/layout/include_chat_pinned_banner.xml");
        Element pinnedText = findById(layout, "pinnedText");
        assertFalse(pinnedText.hasAttributeNS(ANDROID, "maxLines"));
        assertEquals("no", pinnedText.getAttributeNS(ANDROID, "importantForAccessibility"));
    }

    private static Document parse(String relativePath) throws Exception {
        File file = new File(relativePath);
        if (!file.isFile()) {
            file = new File("android", relativePath);
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }

    private static Element findById(Document document, String id) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) {
                return node;
            }
        }
        throw new AssertionError("Missing view " + id);
    }
}
