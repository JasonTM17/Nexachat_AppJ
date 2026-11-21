package com.nexachat.feature.chat;

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
 * Pins the chat-screen fixes for issue #254's second sweep: message rows and
 * the reply quote carry their own descriptions (the #260 merged-row pattern —
 * a flat uiautomator dump cannot observe TalkBack's merged descendant text),
 * the group toolbar names itself when it becomes clickable, and the label-free
 * list/actions containers stop being focus targets while staying visible to
 * a11y services.
 */
public final class ChatRowAccessibilitySourceTest {

    private static final String CONTAINER_SUPPRESSION =
            "A11yContainerRules.suppressFocusTarget";

    @Test
    public void messageBinder_composesRowContentDescription() throws Exception {
        String source = readBinder();
        assertTrue("the clickable bubble row must carry a composed description",
                source.contains("root.setContentDescription(rowAccessibilityDescription(root))"));
        assertTrue(source.contains("String.join(\", \", parts)"));
    }

    @Test
    public void messageBinder_foldsDocPinnedPartsInOrder() throws Exception {
        String source = readBinder();
        int compose = source.indexOf("private String rowAccessibilityDescription");
        assertTrue("expected rowAccessibilityDescription", compose >= 0);
        String body = source.substring(compose);
        assertInOrder(body,
                "addVisibleText(parts, root.findViewById(R.id.senderName))",
                "addVisibleDescription(parts, root.findViewById(R.id.replyQuote))",
                "addPrimaryContent(parts, root)",
                "addVisibleDescription(parts, metaChild(root, R.id.starIcon))",
                "addVisibleText(parts, metaChild(root, R.id.timeLabel))",
                "addVisibleDescription(parts, metaChild(root, R.id.deliveryIcon))",
                "addReactionDescriptions(parts, root)");
    }

    @Test
    public void messageBinder_systemRowsStayPlainLabels() throws Exception {
        String source = readBinder();
        int bind = source.indexOf("root.setContentDescription(rowAccessibilityDescription(root))");
        assertTrue(bind >= 0);
        String window = source.substring(bind, bind + 400);
        assertTrue("system notices are not focus targets and must not carry a row label",
                window.contains("root.setContentDescription(null)"));
    }

    @Test
    public void messageBinder_replyQuoteNamesItself() throws Exception {
        String source = readBinder();
        int quote = source.indexOf("R.string.cd_reply_quote");
        assertTrue("the clickable reply quote needs its own description", quote >= 0);
        String window = source.substring(Math.max(0, quote - 400), quote);
        assertTrue("the quote description composes before the click listener lands",
                window.lastIndexOf("replyInclude.setContentDescription(")
                        >= window.lastIndexOf("replyInclude.setOnClickListener("));
    }

    @Test
    public void chatFragment_groupToolbarCarriesItsTitle() throws Exception {
        String source = readFragment();
        int shortcut = source.indexOf("private void bindGroupInfoShortcut");
        assertTrue("expected bindGroupInfoShortcut", shortcut >= 0);
        String body = source.substring(shortcut, source.indexOf("private void openGroupInfo"));
        assertTrue("the clickable group header must name itself for the flat dump",
                body.contains("binding.chatToolbar.setContentDescription(group ? peerTitle : null)"));
    }

    @Test
    public void chatFragment_suppressesLabelFreeContainers() throws Exception {
        String source = readFragment();
        assertCallsContainerSuppression(source,
                "A11yContainerRules.suppressFocusTarget(list)");
        assertCallsContainerSuppression(source,
                "A11yContainerRules.suppressFocusTarget(\n                binding.chatToolbarActions)");
        assertTrue("the mention picker suppression must stay",
                source.contains("binding.mentionPicker.setFocusable(false)"));
    }

    @Test
    public void unreadBanner_namesItselfFromItsOwnText() throws Exception {
        String source = read("src/main/java/com/nexachat/feature/summary/UnreadBannerView.java");
        assertTrue("the banner tap target must announce the unread-count text it shows",
                source.contains("setContentDescription(bannerText)"));
    }

    @Test
    public void messageBinder_tombstonesUseDeleteGlyphNotBlockUser() throws Exception {
        String source = readBinder();
        int bind = source.indexOf("private void bindPrimaryContent");
        assertTrue("expected bindPrimaryContent", bind >= 0);
        String body = source.substring(bind, source.indexOf("if (kind == MessageContentKind.SYSTEM)", bind));
        assertTrue(body.contains("R.string.message_deleted"));
        assertTrue(body.contains("R.drawable.ic_delete"));
        assertTrue("block-user glyph must not stand in for a deleted message",
                !body.contains("R.drawable.ic_block"));
        assertTrue(body.contains("Typeface.ITALIC"));
    }

    @Test
    public void messageBinder_tombstonesHideDeliveryReceipts() throws Exception {
        String source = readBinder();
        assertTrue("deleted bubbles must not keep sent/read ticks",
                source.contains("if (!isMine || tombstone)"));
    }

    @Test
    public void messageBinder_quotesDeletedOriginalsAsTombstones() throws Exception {
        String source = readBinder();
        assertTrue(source.contains("originalTombstone"));
        assertTrue(source.contains("R.string.message_deleted"));
    }

    @Test
    public void chatFragment_pinnedBannerUsesPlaceholderPreview() throws Exception {
        String source = readFragment();
        int pinned = source.indexOf("private void renderPinned");
        assertTrue(pinned >= 0);
        String body = source.substring(pinned, source.indexOf("private void renderReplyEditBanner"));
        assertTrue(body.contains("MessageUiRules.resolvePlaceholderPreview"));
        assertTrue(body.contains("R.string.message_deleted"));
    }

    @Test
    public void pinnedBannerIcons_carryIdsMarkedDecorative() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(locateLayout().toFile());
        assertImportantForAccessibilityNo(doc, "pinnedIcon");
        assertImportantForAccessibilityNo(doc, "pinnedRefreshIcon");
    }

    @Test
    public void replyQuoteString_existsInBothLocales() throws Exception {
        assertTrue(read("src/main/res/values/strings.xml")
                .contains("<string name=\"cd_reply_quote\">"));
        assertTrue(read("src/main/res/values-vi/strings.xml")
                .contains("<string name=\"cd_reply_quote\">"));
    }

    private static void assertCallsContainerSuppression(String source, String call) {
        int at = source.indexOf(call);
        assertTrue("expected " + call.replace("\n", " "), at >= 0);
        assertTrue("the chat container suppression must route through " + CONTAINER_SUPPRESSION,
                source.contains(CONTAINER_SUPPRESSION));
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
        throw new AssertionError("Missing view id " + id + " in include_chat_pinned_banner.xml");
    }

    private static String readBinder() throws Exception {
        return read("src/main/java/com/nexachat/feature/chat/adapter/MessageBubbleBinder.java");
    }

    private static String readFragment() throws Exception {
        return read("src/main/java/com/nexachat/feature/chat/ChatFragment.java");
    }

    private static Path locateLayout() {
        return locate("src/main/res/layout/include_chat_pinned_banner.xml",
                "android/app/src/main/res/layout/include_chat_pinned_banner.xml",
                "app/src/main/res/layout/include_chat_pinned_banner.xml");
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(locate(
                relative,
                "android/app/" + relative,
                "app/" + relative)), StandardCharsets.UTF_8);
    }

    private static Path locate(String... candidates) {
        for (String relative : candidates) {
            Path path = Path.of(relative);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new AssertionError("Could not locate chat a11y sources");
    }
}
