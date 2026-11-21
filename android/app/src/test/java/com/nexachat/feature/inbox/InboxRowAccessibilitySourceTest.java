package com.nexachat.feature.inbox;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Pins the inbox row's merged accessibility description: the clickable row
 * announces title, time, preview, unread count, then the conditional badge and
 * label descriptions, in the order docs/quality/a11y-critical-screens.md §4.2
 * pins for {@code conversationList} rows. The sweep evidence behind issue #254
 * counted those rows as unlabeled interactive nodes because a flat
 * uiautomator dump cannot observe TalkBack's merged descendant text; the row
 * now carries its own composed label, so both TalkBack and the dump read it.
 */
public final class InboxRowAccessibilitySourceTest {

    private static final String COMPOSED_ROW_LABEL =
            "itemView.setContentDescription(rowAccessibilityDescription())";

    @Test
    public void conversationListAdapter_composesRowContentDescription() throws Exception {
        String source = readAdapterSource();
        assertTrue(source.contains(COMPOSED_ROW_LABEL));
        assertTrue(source.contains("String.join(\", \", parts)"));
    }

    @Test
    public void conversationListAdapter_foldsDocPinnedPartsInOrder() throws Exception {
        String source = readAdapterSource();
        int composition = source.indexOf("private String rowAccessibilityDescription()");
        assertTrue(composition >= 0);
        String body = source.substring(composition);
        assertInOrder(body,
                "title.getContentDescription()",
                "addVisibleText(parts, time)",
                "addVisibleText(parts, preview)",
                "addVisibleDescription(parts, unreadBadge)",
                "addVisibleDescription(parts, streakBadge)",
                "addVisibleDescription(parts, pinIndicator)",
                "addVisibleDescription(parts, muteIcon)",
                "labelChips.getChildAt(index)");
    }

    @Test
    public void conversationListAdapter_refreshesRowDescriptionWhenMuteExpires() throws Exception {
        String source = readAdapterSource();
        int bindCall = source.indexOf(COMPOSED_ROW_LABEL);
        assertTrue(bindCall >= 0);
        assertTrue("mute expiry must re-compose the row description in the refresh runnable",
                source.indexOf(COMPOSED_ROW_LABEL, bindCall + 1) >= 0);
    }

    @Test
    public void inboxRowLayout_keepsDecorationSilencedSoRowLabelIsTheAnnouncement() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(locateLayout().toFile());
        assertImportantForAccessibilityNo(doc, "avatar");
        assertImportantForAccessibilityNo(doc, "previewTypeIcon");
    }

    private static void assertInOrder(String body, String... needles) {
        int at = 0;
        for (String needle : needles) {
            int found = body.indexOf(needle, at);
            assertTrue("expected " + needle + " after position " + at, found >= 0);
            at = found + needle.length();
        }
    }

    private static void assertImportantForAccessibilityNo(Document doc, String id) {
        NodeList views = doc.getElementsByTagName("*");
        for (int i = 0; i < views.getLength(); i++) {
            Element view = (Element) views.item(i);
            if (view.getAttribute("android:id").endsWith("/" + id)) {
                assertTrue(view.getAttribute("android:importantForAccessibility") + " on " + id,
                        "no".equals(view.getAttribute("android:importantForAccessibility")));
                return;
            }
        }
        throw new AssertionError("Missing view id " + id + " in item_inbox_conversation.xml");
    }

    private static String readAdapterSource() throws Exception {
        return new String(Files.readAllBytes(locate(
                "src/main/java/com/nexachat/feature/inbox/adapter/ConversationListAdapter.java",
                "android/app/src/main/java/com/nexachat/feature/inbox/adapter/ConversationListAdapter.java",
                "app/src/main/java/com/nexachat/feature/inbox/adapter/ConversationListAdapter.java")),
                StandardCharsets.UTF_8);
    }

    private static Path locateLayout() {
        return locate(
                "src/main/res/layout/item_inbox_conversation.xml",
                "android/app/src/main/res/layout/item_inbox_conversation.xml",
                "app/src/main/res/layout/item_inbox_conversation.xml");
    }

    private static Path locate(String... candidates) {
        for (String relative : candidates) {
            Path path = Path.of(relative);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new AssertionError("Could not locate inbox row sources");
    }
}
