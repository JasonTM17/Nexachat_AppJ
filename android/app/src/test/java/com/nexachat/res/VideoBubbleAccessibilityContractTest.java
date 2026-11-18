package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The video bubble exposes exactly one action to accessibility services: the play button.
 * The thumbnail is a preview with no click listener, so it must stay out of the accessibility
 * tree, and the play button must name playback rather than the generic attachment action.
 */
public final class VideoBubbleAccessibilityContractTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String[] VIDEO_LAYOUTS = {
            "item_message_video_incoming.xml",
            "item_message_video_outgoing.xml"
    };

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
    public void playButtonNamesPlayback() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String layout : VIDEO_LAYOUTS) {
            Element play = findById(parse(layout), "playButton");
            if (play == null) {
                violations.add(layout + ": missing @id/playButton");
                continue;
            }
            String description = attr(play, "contentDescription");
            if (!"@string/cd_play_video".equals(description)) {
                violations.add(layout + ": playButton contentDescription is \"" + description
                        + "\", expected @string/cd_play_video");
            }
        }
        if (!violations.isEmpty()) {
            fail("Video play button accessibility:\n" + String.join("\n", violations));
        }
    }

    @Test
    public void thumbnailStaysOutOfTheAccessibilityTree() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String layout : VIDEO_LAYOUTS) {
            Element thumbnail = findById(parse(layout), "messageImage");
            if (thumbnail == null) {
                violations.add(layout + ": missing @id/messageImage");
                continue;
            }
            String importance = attr(thumbnail, "importantForAccessibility");
            if (!"no".equals(importance)) {
                violations.add(layout + ": messageImage importantForAccessibility is \"" + importance
                        + "\", expected \"no\"");
            }
            String description = attr(thumbnail, "contentDescription");
            if (!description.isEmpty()) {
                violations.add(layout + ": messageImage still carries contentDescription \""
                        + description + "\" but has no click listener");
            }
        }
        if (!violations.isEmpty()) {
            fail("Video thumbnail accessibility:\n" + String.join("\n", violations));
        }
    }

    @Test
    public void playButtonKeepsTheMinimumTouchTarget() throws Exception {
        for (String layout : VIDEO_LAYOUTS) {
            Element play = findById(parse(layout), "playButton");
            if (play == null) {
                fail(layout + ": missing @id/playButton");
                return;
            }
            assertEquals(layout + ": playButton width", "@dimen/min_touch_target",
                    attr(play, "layout_width"));
            assertEquals(layout + ": playButton height", "@dimen/min_touch_target",
                    attr(play, "layout_height"));
        }
    }

    private org.w3c.dom.Document parse(String layoutName) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new File(new File(resDir, "layout"), layoutName));
    }

    private static Element findById(org.w3c.dom.Document doc, String id) {
        return findById(doc.getDocumentElement(), id);
    }

    private static Element findById(Node node, String id) {
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            return null;
        }
        Element el = (Element) node;
        if (("@+id/" + id).equals(attr(el, "id"))) {
            return el;
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Element found = findById(children.item(i), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String attr(Element el, String localName) {
        String value = el.getAttributeNS(NS_ANDROID, localName);
        if (value == null || value.isEmpty()) {
            value = el.getAttribute("android:" + localName);
        }
        return value == null ? "" : value;
    }
}
