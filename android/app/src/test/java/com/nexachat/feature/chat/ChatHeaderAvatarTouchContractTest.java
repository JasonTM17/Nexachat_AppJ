package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The chat header avatar keeps its 36dp visual size but must expose a >=48dp
 * clickable node: a hit-area container wraps it and carries the click.
 */
public final class ChatHeaderAvatarTouchContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void avatarSitsInsideA48dpHitContainer() throws Exception {
        Document document = parse();
        Element hit = viewById(document, "chatHeaderAvatarHit");
        assertNotNull("chatHeaderAvatarHit container is required", hit);
        assertEquals("@dimen/min_touch_target", hit.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("@dimen/min_touch_target", hit.getAttributeNS(ANDROID, "layout_height"));

        Element avatar = viewById(document, "chatHeaderAvatar");
        assertNotNull(avatar);
        assertEquals("@dimen/avatar_chat_size", avatar.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("@dimen/avatar_chat_size", avatar.getAttributeNS(ANDROID, "layout_height"));

        boolean inside = false;
        NodeList children = hit.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child
                    && ("@+id/chatHeaderAvatar").equals(
                            child.getAttributeNS(ANDROID, "id"))) {
                inside = true;
            }
        }
        assertTrue("the 36dp avatar must be nested inside the 48dp hit container", inside);
        assertTrue("the container is the interactive node; the avatar is decorative",
                "no".equals(avatar.getAttributeNS(ANDROID, "importantForAccessibility")));
    }

    @Test
    public void fragmentBindsClickToTheHitContainer() throws Exception {
        String source = new String(
                java.nio.file.Files.readAllBytes(Path.of(
                        "src/main/java/com/nexachat/feature/chat/ChatFragment.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("clicks and visibility must move to the hit container",
                source.contains("binding.chatHeaderAvatarHit.setOnClickListener")
                        && source.contains("binding.chatHeaderAvatarHit.setClickable")
                        && source.contains("binding.chatHeaderAvatarHit.setVisibility")
                        && source.contains("binding.chatHeaderAvatarHit.setContentDescription"));
    }

    private static Document parse() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(Path.of("src/main/res/layout/fragment_chat.xml").toFile());
    }

    private static Element viewById(Document document, String id) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) {
                return node;
            }
        }
        return null;
    }
}
