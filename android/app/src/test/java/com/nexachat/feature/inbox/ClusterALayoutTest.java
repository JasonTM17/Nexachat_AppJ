package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;

/** Resource contracts for the cluster A actions and scalable unread count. */
public final class ClusterALayoutTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test public void profileEntryHas48dpTarget() throws Exception {
        assertTouchTarget(element("fragment_inbox.xml", "inboxUserAvatar"));
    }

    @Test public void changeAvatarBadgeHas48dpTarget() throws Exception {
        assertTouchTarget(element("fragment_profile.xml", "cameraBadge"));
    }

    @Test public void unreadBadgeCanGrowWithSystemFont() throws Exception {
        Element badge = element("item_inbox_conversation.xml", "unreadBadge");
        assertEquals("wrap_content", badge.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("24dp", badge.getAttributeNS(ANDROID, "minHeight"));
    }

    private static void assertTouchTarget(Element view) {
        for (String axis : new String[] {"layout_width", "layout_height"}) {
            String value = view.getAttributeNS(ANDROID, axis);
            assertTrue(axis + "=" + value, "@dimen/min_touch_target".equals(value)
                    || (value.endsWith("dp")
                    && Integer.parseInt(value.substring(0, value.length() - 2)) >= 48));
        }
    }

    private static Element element(String layout, String id) throws Exception {
        File root = new File("src/main/res/layout");
        if (!root.isDirectory()) root = new File("android/app/src/main/res/layout");
        if (!root.isDirectory()) root = new File("app/src/main/res/layout");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var nodes = factory.newDocumentBuilder().parse(new File(root, layout))
                .getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) return node;
        }
        throw new AssertionError("Missing " + layout + ":" + id);
    }
}
