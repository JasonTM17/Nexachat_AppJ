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

/** Layout contracts for the chat bubble, date chip and message action sheet. */
public final class ChatActionSheetLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void incomingBubbleHasNoStroke() throws Exception {
        Document bubble = parse("src/main/res/drawable/bg_message_bubble_incoming.xml");
        assertEquals("incoming bubble must be a tonal fill without an outline",
                0, bubble.getElementsByTagName("stroke").getLength());
        Element solid = (Element) bubble.getElementsByTagName("solid").item(0);
        assertNotNull(solid);
        assertEquals("@color/bubble_other", solid.getAttributeNS(ANDROID, "color"));
    }

    @Test
    public void dateSeparatorIsAFullyRoundedContainerHighChip() throws Exception {
        Document chip = parse("src/main/res/drawable/bg_date_separator.xml");
        Element corners = (Element) chip.getElementsByTagName("corners").item(0);
        Element solid = (Element) chip.getElementsByTagName("solid").item(0);
        assertNotNull(corners);
        assertNotNull(solid);
        assertEquals("@dimen/corner_full", corners.getAttributeNS(ANDROID, "radius"));
        assertEquals("@color/colorSurfaceContainerHigh", solid.getAttributeNS(ANDROID, "color"));
    }

    @Test
    public void sheetHandleIsARoundedTonalPill() throws Exception {
        Document sheet = parse("src/main/res/layout/sheet_message_actions.xml");
        Element handle = firstChildView(sheet);
        assertEquals("@dimen/sheet_handle_width", handle.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("@dimen/sheet_handle_height", handle.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("@drawable/bg_sheet_handle", handle.getAttributeNS(ANDROID, "background"));

        Document drawable = parse("src/main/res/drawable/bg_sheet_handle.xml");
        Element corners = (Element) drawable.getElementsByTagName("corners").item(0);
        Element solid = (Element) drawable.getElementsByTagName("solid").item(0);
        assertNotNull(corners);
        assertNotNull(solid);
        assertEquals("@dimen/corner_full", corners.getAttributeNS(ANDROID, "radius"));
        assertEquals("?attr/colorSurfaceContainerHighest", solid.getAttributeNS(ANDROID, "color"));
        assertEquals(0, drawable.getElementsByTagName("stroke").getLength());
    }

    @Test
    public void reactionStripIsAnInsetPillThatScrollsInsideItsMargins() throws Exception {
        Document sheet = parse("src/main/res/layout/sheet_message_actions.xml");
        Element scroller = findById(sheet, "reactionScroller");
        Element row = findById(sheet, "reactionRow");

        assertEquals("match_parent", scroller.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("strip must stay inside 16dp side margins",
                "@dimen/space_md", scroller.getAttributeNS(ANDROID, "layout_marginHorizontal"));
        assertEquals("@drawable/bg_reaction_strip", scroller.getAttributeNS(ANDROID, "background"));
        assertEquals("overflow must scroll inside the pill, not off screen",
                "true", scroller.getAttributeNS(ANDROID, "fillViewport"));
        assertEquals("none", scroller.getAttributeNS(ANDROID, "scrollbars"));

        assertEquals("row must size to content so overflow scrolls",
                "wrap_content", row.getAttributeNS(ANDROID, "layout_width"));

        Document strip = parse("src/main/res/drawable/bg_reaction_strip.xml");
        Element corners = (Element) strip.getElementsByTagName("corners").item(0);
        Element solid = (Element) strip.getElementsByTagName("solid").item(0);
        assertNotNull(corners);
        assertNotNull(solid);
        assertEquals("@dimen/corner_full", corners.getAttributeNS(ANDROID, "radius"));
        assertEquals("?attr/colorSurfaceContainerLow", solid.getAttributeNS(ANDROID, "color"));
        assertEquals(0, strip.getElementsByTagName("stroke").getLength());
    }

    @Test
    public void reactionTilesUseSquareRipples() throws Exception {
        String source = readSource("feature/chat/ChatInteractionsViews.java");
        int start = source.indexOf("private static void populateReactionButtons(");
        int end = source.indexOf("private static void confirmDelete(", start);
        assertTrue("reaction tile builder must exist", start >= 0 && end > start);
        String loop = source.substring(start, end);
        assertTrue("each emoji must sit on the bounded square ripple tile",
                loop.contains("R.drawable.bg_reaction_tile"));
        assertTrue("the tile lookup must not re-resolve a theme attr per emoji",
                !loop.contains("resolveAttribute"));
        assertTrue("reaction tiles must keep the 48dp touch target",
                loop.contains("R.dimen.min_touch_target"));

        Document tile = parse("src/main/res/drawable/bg_reaction_tile.xml");
        assertEquals("tile background must be a ripple",
                "ripple", tile.getDocumentElement().getTagName());
        NodeList shapes = tile.getElementsByTagName("shape");
        boolean squareMask = false;
        for (int i = 0; i < shapes.getLength(); i++) {
            Element shape = (Element) shapes.item(i);
            Element item = (Element) shape.getParentNode();
            if ("rectangle".equals(shape.getAttributeNS(ANDROID, "shape"))
                    && "@android:id/mask".equals(
                            item.getAttributeNS(ANDROID, "id"))) {
                squareMask = true;
            }
        }
        assertTrue("ripple must be masked to a square", squareMask);
    }

    @Test
    public void bubbleReactionChipsAreTonalFillsWithoutOutlines() throws Exception {
        String source = readSource("feature/chat/adapter/MessageBubbleBinder.java");
        int start = source.indexOf("private void bindReactions(");
        int end = source.indexOf("private static void hideAll(", start);
        assertTrue("reaction binder must exist", start >= 0 && end > start);
        String body = source.substring(start, end);
        assertTrue("resting chips fill with the high surface container",
                body.contains("R.color.colorSurfaceContainerHigh"));
        assertTrue("selected chips use the secondary container pair",
                body.contains("R.color.colorSecondaryContainer")
                        && body.contains("colorOnSecondaryContainer"));
        assertTrue("chips keep the 12dp shape",
                body.contains("R.dimen.corner_md"));
        assertTrue("chips draw no hairline outline",
                body.contains("setChipStrokeWidth(0f)")
                        && !body.contains("setChipStrokeColor")
                        && !body.contains("colorOutline"));
        assertTrue("reaction chips keep the minimum touch target",
                body.contains("R.dimen.min_touch_target")
                        && body.contains("setEnsureMinTouchTargetSize(true)"));
    }

    @Test
    public void deleteActionKeepsErrorTintedTileAndLabel() throws Exception {
        String source = readSource("feature/chat/ChatInteractionsViews.java");
        int start = source.indexOf("case DELETE ->");
        int end = source.indexOf("default ->", start);
        assertTrue("delete action branch must exist", start >= 0 && end > start);
        String branch = source.substring(start, end);
        assertTrue(branch.contains("R.drawable.bg_settings_tile_error"));
        assertTrue(branch.contains("colorError"));
    }

    @Test
    public void forwardActionIconIsAMirroredReplySwoosh() throws Exception {
        Document forward = parse("src/main/res/drawable/ic_forward.xml");
        Element path = (Element) forward.getElementsByTagName("path").item(0);
        assertNotNull(path);
        String data = path.getAttributeNS(ANDROID, "pathData");
        assertTrue("forward glyph must keep a right-pointing arrow head",
                data.contains("l7,7"));
        assertTrue("forward glyph must sweep a swoosh tail up from the left",
                data.contains("c-5,0"));
        assertFalse("forward glyph must not read as a play triangle",
                data.startsWith("M12,8V4"));
        assertEquals("true",
                forward.getDocumentElement().getAttributeNS(ANDROID, "autoMirrored"));
    }

    @Test
    public void actionRowsBindATitleAndOneLineCaption() throws Exception {
        Document row = parse("src/main/res/layout/item_message_action_row.xml");
        Element caption = findById(row, "actionCaption");
        assertEquals("1", caption.getAttributeNS(ANDROID, "maxLines"));
        assertEquals("end", caption.getAttributeNS(ANDROID, "ellipsize"));

        String source = readSource("feature/chat/ChatInteractionsViews.java");
        int start = source.indexOf("private static void bindMessageActionRow(");
        int end = source.indexOf("private static HorizontalScrollView reactionRow(", start);
        assertTrue("action row binder must exist", start >= 0 && end > start);
        String body = source.substring(start, end);
        assertTrue("binder must fill the caption from the entry",
                body.contains("R.id.actionCaption") && body.contains("actionCaption.setText"));
        assertTrue("delete captions must follow the error tint",
                body.contains("captionAttr"));
    }

    @Test
    public void attachmentPickerIsADesignedBottomSheet() throws Exception {
        String source = readSource("feature/chat/ChatInteractionsViews.java");
        int start = source.indexOf("public static void showAttachmentPicker(");
        int end = source.indexOf("public static void bindReplyBanner(", start);
        assertTrue("attachment picker must exist", start >= 0 && end > start);
        String body = source.substring(start, end);
        assertTrue("attachment picker must use the bottom-sheet chrome",
                body.contains("BottomSheetDialog"));
        assertTrue("attachment picker must inflate the designed sheet",
                body.contains("R.layout.sheet_attachment_picker"));
        assertTrue("attachment rows must be clickable targets",
                body.contains("R.id.pickVisualRow") && body.contains("R.id.pickDocumentRow"));
        assertTrue("attachment picker must not fall back to the plain items dialog",
                !body.contains("setItems"));

        Document sheet = parse("src/main/res/layout/sheet_attachment_picker.xml");
        assertNotNull(findById(sheet, "pickVisualRow"));
        assertNotNull(findById(sheet, "pickDocumentRow"));
    }

    @Test
    public void mentionPickerSitsFlatOnTheSurfaceInBothOrientations() throws Exception {
        for (String directory : new String[]{"layout", "layout-land"}) {
            Document chat = parse("src/main/res/" + directory + "/fragment_chat.xml");
            Element picker = findById(chat, "mentionPicker");
            String elevation = picker.getAttributeNS(ANDROID, "elevation");
            assertTrue(directory + " mentionPicker must not lift above the list",
                    elevation.isEmpty() || "0dp".equals(elevation));
        }
    }

    @Test
    public void composerBannersUseTonalFillsWithoutStrokes() throws Exception {
        Document pinned = parse("src/main/res/layout/include_chat_pinned_banner.xml");
        Element pinnedRoot = findById(pinned, "pinnedRoot");
        assertEquals("?attr/colorSurfaceContainerLow",
                pinnedRoot.getAttributeNS(ANDROID, "background"));
        NodeList pinnedChildren = pinned.getElementsByTagName("*");
        for (int i = 0; i < pinnedChildren.getLength(); i++) {
            Element child = (Element) pinnedChildren.item(i);
            assertFalse("pinned banner must not keep a hairline divider",
                    "?attr/colorOutlineVariant".equals(child.getAttributeNS(ANDROID, "background")));
        }

        Document reply = parse("src/main/res/layout/include_chat_reply_edit_banner.xml");
        Element replyRoot = findById(reply, "replyEditRoot");
        assertEquals("?attr/colorSurfaceContainerLow",
                replyRoot.getAttributeNS(ANDROID, "background"));
    }

    private static Element firstChildView(Document document) {
        NodeList nodes = document.getDocumentElement().getElementsByTagName("View");
        assertTrue("sheet must declare a handle view", nodes.getLength() > 0);
        return (Element) nodes.item(0);
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
