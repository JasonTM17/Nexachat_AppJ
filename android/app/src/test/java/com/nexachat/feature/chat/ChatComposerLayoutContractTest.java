package com.nexachat.feature.chat;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Structural checks for input growth and composer context in both orientations. */
public final class ChatComposerLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    private static Element view(String file, String id) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder().parse(Path.of("src/main/res", file).toFile())
                .getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) return node;
        }
        throw new AssertionError("Missing " + id + " in " + file);
    }

    @Test public void composerAllowsNewlinesAndGrowsWithinSixLines() throws Exception {
        Element input = view("layout/include_chat_composer_bar.xml", "messageInput");
        assertTrue(input.getAttributeNS(ANDROID, "inputType").contains("textMultiLine"));
        assertEquals("wrap_content", input.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("6", input.getAttributeNS(ANDROID, "maxLines"));
        assertEquals("actionNone", input.getAttributeNS(ANDROID, "imeOptions"));
    }

    @Test public void replyContextIsAdjacentToInputInBothOrientations() throws Exception {
        for (String dir : new String[]{"layout", "layout-land"}) {
            Element banner = view(dir + "/fragment_chat.xml", "replyEditBanner");
            assertEquals("@id/composerBar", banner.getAttributeNS(APP, "layout_constraintBottom_toTopOf"));
            Element error = view(dir + "/fragment_chat.xml", "chatError");
            assertEquals("@id/replyEditBanner", error.getAttributeNS(APP, "layout_constraintBottom_toTopOf"));
            Element unread = view(dir + "/fragment_chat.xml", "unreadBanner");
            assertEquals("@id/pinnedBanner", unread.getAttributeNS(APP, "layout_constraintTop_toBottomOf"));
        }
    }

    @Test public void forwardPickerHasBoundedListAndSelectionGate() throws Exception {
        Element list = view("layout/dialog_forward.xml", "forwardList");
        assertEquals("0dp", list.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("1", list.getAttributeNS(ANDROID, "layout_weight"));
        assertEquals("false", view("layout/dialog_forward.xml", "forwardSend").getAttributeNS(ANDROID, "enabled"));
    }
}
