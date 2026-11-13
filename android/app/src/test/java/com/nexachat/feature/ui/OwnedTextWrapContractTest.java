package com.nexachat.feature.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * User-written titles and the invite share actions must wrap at font scale 1.3
 * on a 320dp-wide screen instead of ending in an ellipsis.
 */
public final class OwnedTextWrapContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void inviteShareLabelsNeedTwoLinesAtFontScaleOnePointThree() throws Exception {
        String copy = vietnamese("invite_copy");
        String share = vietnamese("invite_share");
        int textWidthDp = Math.max(estimateDp(copy), estimateDp(share));
        int innerWidthDp = shareButtonInnerWidthDp();
        assertTrue(
                "expected share labels to overflow one line, width "
                        + textWidthDp + "dp > " + innerWidthDp + "dp",
                textWidthDp > innerWidthDp);
        assertEquals("2", view("layout/fragment_invite_manage.xml", "copyButton")
                .getAttributeNS(ANDROID, "maxLines"));
        assertEquals("2", view("layout/fragment_invite_manage.xml", "shareButton")
                .getAttributeNS(ANDROID, "maxLines"));
    }

    @Test
    public void reminderTitlesAndPollQuestionsAreNotCappedAtTwoOrFourLines() throws Exception {
        assertFalse(view("layout/item_reminder.xml", "reminderTitle").hasAttributeNS(ANDROID, "maxLines"));
        assertFalse(view("layout/item_poll.xml", "pollQuestion").hasAttributeNS(ANDROID, "maxLines"));
    }

    @Test
    public void landscapeCallNameUsesTwoLines() throws Exception {
        assertEquals("2", view("layout-land/activity_call.xml", "call_peer_name")
                .getAttributeNS(ANDROID, "maxLines"));
    }

    /** 320dp screen, 16dp page inset, 16dp card inset, 8dp gap, then button padding. */
    private static int shareButtonInnerWidthDp() {
        int button = (320 - 32 - 32 - 8) / 2;
        return button - 32;
    }

    /** LabelLarge 14sp at font scale 1.3, about half an em per character. */
    private static int estimateDp(String text) {
        return (int) Math.ceil(text.length() * 14d * 1.3d * 0.5d);
    }

    private static String vietnamese(String name) throws Exception {
        String xml = new String(
                Files.readAllBytes(locateRes("values-vi/strings_invite.xml")), StandardCharsets.UTF_8);
        String token = "name=\"" + name + "\">";
        int start = xml.indexOf(token);
        assertTrue(start > 0);
        start += token.length();
        int end = xml.indexOf("</string>", start);
        return xml.substring(start, end).trim();
    }

    private static Element view(String file, String id) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder()
                .parse(locateRes(file).toFile())
                .getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) {
                return node;
            }
        }
        throw new AssertionError("Missing " + id + " in " + file);
    }

    private static Path locateRes(String relative) {
        Path[] candidates = new Path[] {
                Path.of("src/main/res", relative),
                Path.of("app/src/main/res", relative),
                Path.of("android/app/src/main/res", relative)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Missing " + relative);
    }
}
