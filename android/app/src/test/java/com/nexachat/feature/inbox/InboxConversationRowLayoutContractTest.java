package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

/** Inbox row avatar must stay top-aligned when label chips grow the row. */
public final class InboxConversationRowLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    @Test
    public void conversationRowAvatarIsTopAlignedNotVerticallyCentered() throws Exception {
        Element avatar = requireId("item_inbox_conversation.xml", "avatar");
        assertEquals("parent", avatar.getAttributeNS(APP, "layout_constraintTop_toTopOf"));
        String bottomTarget = avatar.getAttributeNS(APP, "layout_constraintBottom_toBottomOf");
        assertNotEquals("parent", bottomTarget);
    }

    @Test
    public void inboxListHasNoTopPaddingBand() throws Exception {
        Element list = requireId("fragment_inbox.xml", "conversationList");
        String topPadding = list.getAttributeNS(ANDROID, "paddingTop");
        assertEquals("0dp", topPadding.isEmpty() ? "0dp" : topPadding);
    }

    private static Element requireId(String file, String id) throws Exception {
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
