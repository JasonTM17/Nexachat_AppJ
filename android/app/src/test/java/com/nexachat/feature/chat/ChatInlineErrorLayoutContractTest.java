package com.nexachat.feature.chat;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class ChatInlineErrorLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    @Test
    public void inlineErrorUsesTonalContainerInBothOrientations() throws Exception {
        for (String directory : new String[]{"layout", "layout-land"}) {
            Document layout = parse("src/main/res/" + directory + "/fragment_chat.xml");
            Element error = findById(layout, "chatError");
            assertNotNull(error);
            assertEquals("?attr/colorOnErrorContainer", error.getAttributeNS(ANDROID, "textColor"));
            assertEquals("@drawable/bg_auth_inline_error", error.getAttributeNS(ANDROID, "background"));
            assertEquals("@id/replyEditBanner", error.getAttributeNS(APP, "layout_constraintBottom_toTopOf"));
            Element reply = findById(layout, "replyEditBanner");
            assertEquals("@id/composerBar", reply.getAttributeNS(APP, "layout_constraintBottom_toTopOf"));

            Element messageList = findById(layout, "messageList");
            assertEquals("@id/mentionPicker",
                    messageList.getAttributeNS(APP, "layout_constraintBottom_toTopOf"));
            Element mentionPicker = findById(layout, "mentionPicker");
            assertEquals("@id/pendingAttachments",
                    mentionPicker.getAttributeNS(APP, "layout_constraintBottom_toTopOf"));
            Element pendingAttachments = findById(layout, "pendingAttachments");
            assertEquals("@id/chatError",
                    pendingAttachments.getAttributeNS(APP, "layout_constraintBottom_toTopOf"));
        }
    }

    @Test
    public void retryButtonIsInsideTheExistingErrorSurfaceWithExistingStyle() throws Exception {
        for (String directory : new String[]{"layout", "layout-land"}) {
            Document layout = parse("src/main/res/" + directory + "/fragment_chat.xml");
            Element error = findById(layout, "chatError");
            Element message = findById(layout, "chatErrorMessage");
            Element retry = findById(layout, "chatErrorRetry");

            assertEquals(error, message.getParentNode());
            assertEquals(error, retry.getParentNode());
            assertEquals("?attr/colorOnErrorContainer", message.getAttributeNS(ANDROID, "textColor"));
            assertEquals("@string/action_retry", retry.getAttributeNS(ANDROID, "text"));
            assertEquals("@style/Widget.NexaChat.Button.Text", retry.getAttribute("style"));
            assertEquals("@dimen/min_touch_target", retry.getAttributeNS(ANDROID, "minHeight"));
            assertEquals("gone", retry.getAttributeNS(ANDROID, "visibility"));
        }
    }

    @Test
    public void inlineErrorBackgroundUsesTwelveDpErrorContainerShape() throws Exception {
        Document background = parse("src/main/res/drawable/bg_auth_inline_error.xml");
        Element corners = (Element) background.getElementsByTagName("corners").item(0);
        Element solid = (Element) background.getElementsByTagName("solid").item(0);
        assertEquals("@dimen/corner_md", corners.getAttributeNS(ANDROID, "radius"));
        assertEquals("?attr/colorErrorContainer", solid.getAttributeNS(ANDROID, "color"));
    }

    @Test
    public void composerTextInsetUsesExistingTwelveDpToken() throws Exception {
        Document composer = parse("src/main/res/layout/include_chat_composer_bar.xml");
        Element messageInput = findById(composer, "messageInput");
        assertEquals("@dimen/bubble_content_inset", messageInput.getAttributeNS(ANDROID, "paddingHorizontal"));

        Document dimensions = parse("src/main/res/values/dimens_nexachat.xml");
        Element inset = findResource(dimensions, "dimen", "bubble_content_inset");
        assertEquals("12dp", inset.getTextContent().trim());
    }

    @Test
    public void composerAttachMicAndSendTargetsRemainFortyEightDp() throws Exception {
        Document composer = parse("src/main/res/layout/include_chat_composer_bar.xml");
        Element attach = findById(composer, "attachButton");
        assertEquals("@dimen/min_touch_target", attach.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("@dimen/min_touch_target", attach.getAttributeNS(ANDROID, "layout_height"));

        Element actionSlot = findById(composer, "composerActionSlot");
        assertEquals("@dimen/min_touch_target", actionSlot.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("@dimen/min_touch_target", actionSlot.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("match_parent", findById(composer, "micButton").getAttributeNS(ANDROID, "layout_width"));
        assertEquals("match_parent", findById(composer, "micButton").getAttributeNS(ANDROID, "layout_height"));
        assertEquals("match_parent", findById(composer, "sendButton").getAttributeNS(ANDROID, "layout_width"));
        assertEquals("match_parent", findById(composer, "sendButton").getAttributeNS(ANDROID, "layout_height"));

        Document dimensions = parse("src/main/res/values/dimens_nexachat.xml");
        Element target = findResource(dimensions, "dimen", "min_touch_target");
        assertEquals("48dp", target.getTextContent().trim());
    }

    private static Document parse(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(path));
    }

    private static Element findById(Document document, String id) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) return node;
        }
        throw new AssertionError("Missing view " + id);
    }

    private static Element findResource(Document document, String tag, String name) {
        NodeList nodes = document.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (name.equals(node.getAttribute("name"))) return node;
        }
        throw new AssertionError("Missing " + tag + " " + name);
    }
}
