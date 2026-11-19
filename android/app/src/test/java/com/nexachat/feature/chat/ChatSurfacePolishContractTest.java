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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Contracts for the chat-surface polish: stacked bubble corners, tonal
 * composer surfaces, the pinned banner hierarchy and the unread banner card.
 */
public final class ChatSurfacePolishContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    private static final String[] MEDIA_BUBBLE_LAYOUTS = {
            "item_message_audio_incoming", "item_message_audio_outgoing",
            "item_message_file_incoming", "item_message_file_outgoing",
            "item_message_image_incoming", "item_message_image_outgoing",
            "item_message_video_incoming", "item_message_video_outgoing"
    };

    @Test
    public void bubbleCornersComeFromTheSharedMergeRule() throws Exception {
        String source = readSource("feature/chat/adapter/MessageBubbleBinder.java");
        int start = source.indexOf("private void applyBubbleShape(");
        int end = source.indexOf("private void applyRowSpacing(", start);
        assertTrue("bubble shape binder must exist", start >= 0 && end > start);
        String body = source.substring(start, end);
        assertTrue("sender-side merge must come from ChatBubbleRules",
                body.contains("ChatBubbleRules.bubbleMergedCorners(position, tailOnRight)"));
        assertTrue("corner radii must still honour the dimens",
                body.contains("R.dimen.corner_xl") && body.contains("R.dimen.corner_xs"));
    }

    @Test
    public void mediaBubblesPadContentWithSharedTokens() throws Exception {
        for (String layout : MEDIA_BUBBLE_LAYOUTS) {
            Document doc = parse("src/main/res/layout/" + layout + ".xml");
            Element bubble = findById(doc, "bubbleContainer");
            assertEquals(layout + " must pad with the shared horizontal inset",
                    "@dimen/bubble_content_inset", bubble.getAttributeNS(ANDROID, "paddingHorizontal"));
            assertEquals(layout + " must pad with the shared vertical space",
                    "@dimen/space_sm", bubble.getAttributeNS(ANDROID, "paddingVertical"));
        }
    }

    @Test
    public void mediaPlayButtonRidesOnTheSharedScrim() throws Exception {
        for (String layout : new String[]{
                "item_message_image_incoming", "item_message_image_outgoing",
                "item_message_video_incoming", "item_message_video_outgoing"}) {
            Element play = findById(
                    parse("src/main/res/layout/" + layout + ".xml"), "playButton");
            assertEquals(layout + " play affordance must sit on the media scrim",
                    "@drawable/bg_media_play_button", play.getAttributeNS(ANDROID, "background"));
            assertEquals(layout + " play glyph must read on any photo",
                    "@color/nexa_on_media_meta", play.getAttributeNS(APP, "tint"));
        }

        Document scrim = parse("src/main/res/drawable/bg_media_play_button.xml");
        Element solid = (Element) scrim.getElementsByTagName("solid").item(0);
        assertNotNull(solid);
        assertEquals("@color/nexa_media_meta_surface", solid.getAttributeNS(ANDROID, "color"));
        assertEquals("oval", scrim.getDocumentElement().getAttributeNS(ANDROID, "shape"));
    }

    @Test
    public void composerFieldAndRecordingPillShareTheFilledPillShape() throws Exception {
        for (String drawable : new String[]{"bg_composer_field", "bg_composer_pill"}) {
            Document doc = parse("src/main/res/drawable/" + drawable + ".xml");
            Element solid = (Element) doc.getElementsByTagName("solid").item(0);
            Element corners = (Element) doc.getElementsByTagName("corners").item(0);
            assertNotNull(drawable + " needs a solid fill", solid);
            assertNotNull(drawable + " needs corners", corners);
            assertEquals(drawable + " must use the high container fill",
                    "@color/colorSurfaceContainerHigh", solid.getAttributeNS(ANDROID, "color"));
            assertEquals(drawable + " must be a full pill",
                    "@dimen/corner_full", corners.getAttributeNS(ANDROID, "radius"));
        }
    }

    @Test
    public void composerTopSeparatorUsesTheSoftDividerToken() throws Exception {
        Document doc = parse("src/main/res/drawable/bg_chat_composer.xml");
        NodeList solids = doc.getElementsByTagName("solid");
        boolean softDivider = false;
        for (int i = 0; i < solids.getLength(); i++) {
            Element solid = (Element) solids.item(i);
            if ("@color/colorSurfaceContainerHighest".equals(solid.getAttributeNS(ANDROID, "color"))) {
                softDivider = true;
            }
            assertFalse("composer separator must not be a hard outline",
                    "@color/colorOutlineVariant".equals(solid.getAttributeNS(ANDROID, "color")));
        }
        assertTrue("composer separator must use the soft divider token", softDivider);
    }

    @Test
    public void recordingOverlaySharesFieldMetricsAndInterType() throws Exception {
        Document composer = parse("src/main/res/layout/include_chat_composer_bar.xml");
        Element overlay = findById(composer, "recordingOverlay");
        assertEquals("@dimen/bubble_content_inset",
                overlay.getAttributeNS(ANDROID, "paddingHorizontal"));
        assertEquals("@dimen/space_sm", overlay.getAttributeNS(ANDROID, "paddingVertical"));

        NodeList nodes = composer.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            assertFalse("composer text must use the themed type scale, not a bundled font",
                    node.hasAttributeNS(ANDROID, "fontFamily"));
        }
    }

    @Test
    public void unreadBannerIsAFlatTonalCardWithTintedIcon() throws Exception {
        Document doc = parse("src/main/res/layout/include_unread_banner.xml");
        Element card = doc.getDocumentElement();
        assertEquals("unread banner must not cast a shadow",
                "0dp", card.getAttributeNS(APP, "cardElevation"));
        assertEquals("?attr/colorSurfaceContainerHigh",
                card.getAttributeNS(APP, "cardBackgroundColor"));
        assertEquals("@dimen/corner_xl", card.getAttributeNS(APP, "cardCornerRadius"));

        Element icon = firstTag(card, "ImageView");
        assertEquals("?attr/colorPrimary", icon.getAttributeNS(APP, "tint"));

        Element text = findById(card, "unreadBannerText");
        assertEquals("?attr/textAppearanceBodyMedium",
                text.getAttributeNS(ANDROID, "textAppearance"));
        assertEquals("?attr/colorOnSurface", text.getAttributeNS(ANDROID, "textColor"));
    }

    @Test
    public void pinnedBannerHasLabelAndJumpAffordance() throws Exception {
        Document doc = parse("src/main/res/layout/include_chat_pinned_banner.xml");

        Element title = findById(doc, "pinnedTitle");
        assertEquals("@string/pinned_title", title.getAttributeNS(ANDROID, "text"));
        assertEquals("?attr/textAppearanceLabelSmall",
                title.getAttributeNS(ANDROID, "textAppearance"));
        assertEquals("?attr/colorPrimary", title.getAttributeNS(ANDROID, "textColor"));
        assertEquals("the root announces the banner; the label must stay out of the tree",
                "no", title.getAttributeNS(ANDROID, "importantForAccessibility"));

        boolean cycleIcon = false;
        NodeList images = doc.getElementsByTagName("ImageView");
        for (int i = 0; i < images.getLength(); i++) {
            Element image = (Element) images.item(i);
            if ("@drawable/ic_refresh".equals(image.getAttributeNS(ANDROID, "src"))) {
                cycleIcon = true;
                assertEquals("the cycle affordance is decorative",
                        "no", image.getAttributeNS(ANDROID, "importantForAccessibility"));
            }
        }
        assertTrue("pinned banner must show the cycle-to-next-pin icon", cycleIcon);

        Element count = findById(doc, "pinnedCount");
        assertEquals("the position indicator is announced by the root description",
                "no", count.getAttributeNS(ANDROID, "importantForAccessibility"));
    }

    @Test
    public void toolbarOverflowUsesTheVariantTintInBothOrientations() throws Exception {
        for (String dir : new String[]{"layout", "layout-land"}) {
            Document doc = parse("src/main/res/" + dir + "/fragment_chat.xml");
            Element more = findById(doc, "moreButton");
            assertEquals(dir + " trailing actions use the variant tint",
                    "?attr/colorOnSurfaceVariant", more.getAttributeNS(APP, "tint"));
        }
    }

    @Test
    public void systemNoticeAndUnreadChipUseSharedPaddingTokens() throws Exception {
        Document system = parse("src/main/res/layout/item_message_system.xml");
        Element chip = findById(system, "bubbleContainer");
        assertEquals("@dimen/space_inset", chip.getAttributeNS(ANDROID, "paddingHorizontal"));
        assertEquals("@dimen/space_xs", chip.getAttributeNS(ANDROID, "paddingVertical"));

        Document divider = parse("src/main/res/layout/item_unread_divider.xml");
        Element label = findById(divider, "unreadDividerLabel");
        assertEquals("@dimen/space_inset", label.getAttributeNS(ANDROID, "paddingHorizontal"));
        assertEquals("@dimen/space_xs", label.getAttributeNS(ANDROID, "paddingVertical"));
        assertFalse("the drawable already fills containerHigh; a redundant tint hides drift",
                label.hasAttributeNS(ANDROID, "backgroundTint"));
    }

    @Test
    public void reactionRowSpacingComesFromTheGroupAlone() throws Exception {
        Document row = parse("src/main/res/layout/include_message_reactions_row.xml");
        Element group = findById(row, "reactionChipGroup");
        assertEquals("@dimen/space_sm", group.getAttributeNS(APP, "chipSpacing"));

        String source = readSource("feature/chat/adapter/MessageBubbleBinder.java");
        int start = source.indexOf("private void bindReactions(");
        int end = source.indexOf("private static void hideAll(", start);
        assertTrue("reaction binder must exist", start >= 0 && end > start);
        String body = source.substring(start, end);
        assertFalse("chip spacing must come from the ChipGroup, not per-chip margins",
                body.contains("setMarginStart"));
    }

    @Test
    public void chatLayoutsUseThemeColoursOnly() throws Exception {
        String[] layouts = {
                "layout/fragment_chat.xml", "layout-land/fragment_chat.xml",
                "layout/include_chat_composer_bar.xml", "layout/include_chat_pinned_banner.xml",
                "layout/include_chat_reply_edit_banner.xml", "layout/include_unread_banner.xml",
                "layout/include_message_bubble_meta.xml", "layout/include_message_reactions_row.xml",
                "layout/include_message_reply_quote.xml", "layout/include_message_voice_player.xml",
                "layout/item_date_separator.xml", "layout/item_unread_divider.xml",
                "layout/item_message_system.xml", "layout/item_message_action_row.xml"
        };
        for (String layout : MEDIA_BUBBLE_LAYOUTS) {
            assertFalse(layout + " uses a raw colour",
                    read("src/main/res/layout/" + layout + ".xml").contains("\"#"));
        }
        for (String layout : layouts) {
            assertFalse(layout + " uses a raw colour",
                    read("src/main/res/" + layout).contains("\"#"));
        }
    }

    private static Document parse(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(path));
    }

    private static Element findById(Document document, String id) {
        return findById(document.getDocumentElement(), id);
    }

    private static Element findById(Element root, String id) {
        if (("@+id/" + id).equals(root.getAttributeNS(ANDROID, "id"))) return root;
        NodeList nodes = root.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) return node;
        }
        throw new AssertionError("Missing view " + id);
    }

    private static Element firstTag(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            throw new AssertionError("Missing " + tag);
        }
        return (Element) nodes.item(0);
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
