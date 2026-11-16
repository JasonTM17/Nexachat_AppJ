package com.nexachat.feature.chat;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contracts for the inbox/chat sweep: reply-quote readability, search empty
 * state, bubble width caps, Material indicators, themed call type scale,
 * anchored feedback and voice-recording survival across view recreation.
 */
public final class ChatInboxSweepContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    private static final String[] BUBBLE_LAYOUTS = {
            "item_message_audio_incoming", "item_message_audio_outgoing",
            "item_message_file_incoming", "item_message_file_outgoing",
            "item_message_image_incoming", "item_message_image_outgoing",
            "item_message_system",
            "item_message_text_incoming", "item_message_text_outgoing",
            "item_message_video_incoming", "item_message_video_outgoing"
    };

    @Test
    public void replyQuoteDrivesBubbleWidthAndBoundsItsText() throws Exception {
        // match_parent children are re-measured EXACTLY to the bubble's width,
        // so the minimum must be applied to the bubble itself by the binder.
        String binder = readSource("feature/chat/adapter/MessageBubbleBinder.java");
        int start = binder.indexOf("private void bindReplyQuote(");
        int end = binder.indexOf("private void tintReplyQuote(", start);
        assertTrue("reply binder must exist", start >= 0 && end > start);
        String body = binder.substring(start, end);
        assertTrue("visible quote must raise the bubble to the shared min",
                body.contains("setMinimumWidth(quoteMin)")
                        && body.contains("R.dimen.bubble_quote_min_width"));
        assertTrue("hidden quote must release the minimum for recycled views",
                body.contains("setMinimumWidth(0)"));

        Document doc = parse("src/main/res/layout/include_message_reply_quote.xml");
        Element author = findById(doc, "replyAuthor");
        assertEquals("1", author.getAttributeNS(ANDROID, "maxLines"));
        assertEquals("end", author.getAttributeNS(ANDROID, "ellipsize"));

        Element snippet = findById(doc, "replySnippet");
        assertEquals("quoted text must cap instead of stacking one word per line",
                "3", snippet.getAttributeNS(ANDROID, "maxLines"));
        assertEquals("end", snippet.getAttributeNS(ANDROID, "ellipsize"));
    }

    @Test
    public void chatEmptyStateAnchorsToStableRegionNotTheList() throws Exception {
        for (String dir : new String[]{"layout", "layout-land"}) {
            Document doc = parse("src/main/res/" + dir + "/fragment_chat.xml");
            Element empty = findById(doc, "emptyState");
            // messageList goes GONE for no-results; anchoring to it collapses
            // the empty view to zero height.
            assertFalse(dir + " emptyState must not depend on messageList",
                    empty.getAttributeNS(APP, "layout_constraintTop_toTopOf")
                            .contains("messageList"));
            assertFalse(dir + " emptyState must not depend on messageList",
                    empty.getAttributeNS(APP, "layout_constraintBottom_toBottomOf")
                            .contains("messageList"));
            assertEquals(dir + " emptyState must fill the message region",
                    "@id/chatSearchBar",
                    empty.getAttributeNS(APP, "layout_constraintTop_toBottomOf"));
            assertEquals(dir + " emptyState must fill the message region",
                    "@id/mentionPicker",
                    empty.getAttributeNS(APP, "layout_constraintBottom_toTopOf"));
        }
    }

    @Test
    public void bubblesWrapContentButNeverExceedTheSharedMax() throws Exception {
        for (String layout : BUBBLE_LAYOUTS) {
            Element bubble = findById(
                    parse("src/main/res/layout/" + layout + ".xml"), "bubbleContainer");
            assertEquals(layout + " bubble must size from constraints, not raw wrap",
                    "0dp", bubble.getAttributeNS(ANDROID, "layout_width"));
            assertEquals(layout + " bubble must keep wrap semantics",
                    "wrap", bubble.getAttributeNS(APP, "layout_constraintWidth_default"));
            assertEquals(layout + " bubble must honour the shared width token",
                    "@dimen/bubble_max_width",
                    bubble.getAttributeNS(APP, "layout_constraintWidth_max"));
        }
    }

    @Test
    public void chatAreaShowsAnchoredSnackbarsNotRawToasts() throws Exception {
        for (String file : new String[]{
                "feature/chat/ChatFragment.java",
                "feature/archive/ArchiveFragment.java",
                "feature/summary/SummaryBottomSheetFragment.java"}) {
            String source = readSource(file);
            assertFalse(file + " must not pop unanchored Toasts",
                    source.contains("Toast.makeText"));
        }
        String chat = readSource("feature/chat/ChatFragment.java");
        assertTrue("composer feedback must anchor to the composer bar",
                chat.contains("setAnchorView(binding.composerBar.getRoot())"));
    }

    @Test
    public void callScreensUseThemedTypeScaleOnly() throws Exception {
        for (String layout : new String[]{
                "activity_call.xml", "activity_incoming_call.xml"}) {
            String xml = read("src/main/res/layout/" + layout);
            assertFalse(layout + " must not carry raw sp text sizes",
                    xml.contains("android:textSize"));
            assertTrue(layout + " must use theme text appearances",
                    xml.contains("android:textAppearance=\"?attr/textAppearance"));
        }
    }

    @Test
    public void chatAreaProgressUsesMaterialIndicators() throws Exception {
        for (String layout : new String[]{
                "fragment_archive.xml", "fragment_poll.xml",
                "fragment_invite_join.xml", "fragment_invite_manage.xml",
                "include_message_bubble_meta.xml",
                "item_poll_option_single.xml", "item_poll_option_multiple.xml"}) {
            assertFalse(layout + " must not use the framework ProgressBar",
                    read("src/main/res/layout/" + layout).contains("<ProgressBar"));
        }
        assertTrue("contact detail spinner must be a Material indicator",
                readSource("feature/contacts/ContactDetailBinder.java")
                        .contains("CircularProgressIndicator"));
        assertFalse("poll vote bars must use the Material linear indicator",
                readSource("feature/poll/PollAdapter.java")
                        .contains("android.widget.ProgressBar"));
    }

    @Test
    public void voiceRecorderOutlivesViewRecreation() throws Exception {
        String vm = readSource("feature/chat/ChatViewModel.java");
        assertTrue("ViewModel must own the recorder",
                vm.contains("getOrCreateVoiceRecorder"));
        assertTrue("ViewModel must expose the retention flag",
                vm.contains("setVoiceRetainedForConfigChange"));
        assertTrue("ViewModel must release the recorder on clear",
                vm.contains("releaseVoiceRecorder();"));

        String fragment = readSource("feature/chat/ChatFragment.java");
        assertTrue("fragment must distinguish config change from real destroy",
                fragment.contains("isChangingConfigurations()"));
        assertFalse("fragment must not construct recorders directly",
                fragment.contains("new VoiceRecorder("));
        assertTrue("recreated view must re-attach the recording HUD",
                fragment.contains("resumeVoiceRecordingAfterConfigChange"));
        assertTrue("teardown-cancelled touch must retain, not cancel, the capture",
                fragment.indexOf("isChangingConfigurations()") > 0
                        && fragment.contains("setVoiceRetainedForConfigChange(true)"));
    }

    @Test
    public void chatInputsNeverTriggerImeExtractMode() throws Exception {
        // In landscape the IME fullscreen extracted editor hides the whole
        // chat behind a giant text field; chat-area inputs must opt out.
        String[][] cases = {
                {"src/main/res/layout/fragment_chat.xml", "chatSearchInput"},
                {"src/main/res/layout-land/fragment_chat.xml", "chatSearchInput"},
                {"src/main/res/layout/include_chat_composer_bar.xml", "messageInput"},
                {"src/main/res/layout/dialog_forward.xml", "forwardSearch"},
                {"src/main/res/layout/bottom_sheet_ask_chat.xml", "askQuestion"},
                {"src/main/res/layout/fragment_user_search.xml", "searchInput"},
        };
        for (String[] c : cases) {
            Element input = findById(parse(c[0]), c[1]);
            String options = input.getAttributeNS(ANDROID, "imeOptions");
            assertTrue(c[0] + "#" + c[1] + " must keep its UI while typing",
                    options.contains("flagNoExtractUi"));
        }
    }

    private static Document parse(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(path));
    }

    private static Element findById(Document document, String id) {
        NodeList nodes = document.getDocumentElement().getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) return node;
        }
        throw new AssertionError("Missing view " + id);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static String readSource(String relativePath) throws Exception {
        File[] candidates = new File[]{
                new File("src/main/java/com/nexachat/" + relativePath),
                new File("app/src/main/java/com/nexachat/" + relativePath),
                new File("android/app/src/main/java/com/nexachat/" + relativePath),
                new File("../app/src/main/java/com/nexachat/" + relativePath)
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Could not locate source " + relativePath);
    }
}
