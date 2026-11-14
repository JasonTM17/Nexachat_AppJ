package com.nexachat.feature.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

/** Profile hero avatar camera badge stays fully visible. */
public final class ProfileCameraBadgeLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    /**
     * The badge overhangs the avatar rim, but inside a frame larger than the avatar. A negative
     * margin pushed it past the frame, where the frame cut it flat on a device.
     */
    @Test
    public void avatarFrameIsLargerThanTheAvatarSoTheBadgeIsNeverClipped() throws Exception {
        Element avatar = requireId("fragment_profile.xml", "profileAvatar");
        Element frame = findAvatarBadgeParent();
        assertEquals("96dp", avatar.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("108dp", frame.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("108dp", frame.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("top|start", avatar.getAttributeNS(ANDROID, "layout_gravity"));
    }

    @Test
    public void cameraBadgeStaysReachableAndInsideItsFrame() throws Exception {
        Element badge = requireId("fragment_profile.xml", "cameraBadge");
        assertEquals("@dimen/min_touch_target", badge.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("@dimen/min_touch_target", badge.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("@string/profile_change_avatar", badge.getAttributeNS(ANDROID, "contentDescription"));
        assertEquals("bottom|end", badge.getAttributeNS(ANDROID, "layout_gravity"));
        assertFalse(badge.getAttributeNS(ANDROID, "layout_marginEnd").startsWith("-"));
        assertFalse(badge.getAttributeNS(ANDROID, "layout_marginBottom").startsWith("-"));
    }

    private static Element findAvatarBadgeParent() throws Exception {
        Element avatar = requireId("fragment_profile.xml", "profileAvatar");
        assertNotNull(avatar.getParentNode());
        return (Element) avatar.getParentNode();
    }

    private static Element requireId(String file, String id) throws Exception {
        return view(file, id);
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
