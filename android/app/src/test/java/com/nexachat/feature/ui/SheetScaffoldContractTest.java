package com.nexachat.feature.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Scaffold contract: owned sheets share the surface drawable and handle
 * include, raw BottomSheetDialog call sites are replaced by fragments shown
 * through the child FragmentManager, and the shared base owns expand,
 * keyboard resize, and the wide-screen width cap.
 */
public final class SheetScaffoldContractTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

    private static final String[] SCAFFOLD_SHEETS = {
            "layout/sheet_message_actions.xml",
            "layout/sheet_reminder_editor.xml",
            "layout/sheet_attachment_picker.xml",
            "layout/dialog_forward.xml",
            "layout/bottom_sheet_group_add_member.xml",
            "layout/bottom_sheet_summary.xml",
            "layout/bottom_sheet_ask_chat.xml"
    };

    @Test
    public void everyScaffoldSheetUsesTheSharedSurfaceAndHandle() throws Exception {
        for (String relative : SCAFFOLD_SHEETS) {
            Document doc = parse(res(relative));
            assertEquals(relative + " must carry the shared sheet surface",
                    "@drawable/bg_sheet_surface",
                    doc.getDocumentElement().getAttributeNS(NS_ANDROID, "background"));
            assertTrue(relative + " must include the shared drag handle",
                    hasHandleInclude(doc));
        }
    }

    @Test
    public void sharedBaseExpandsResizesAndCapsWidth() throws Exception {
        String base = read("com/nexachat/feature/ui/NexaBottomSheetFragment.java");
        assertTrue(base.contains("extends BottomSheetDialogFragment"));
        assertTrue(base.contains("STATE_EXPANDED"));
        assertTrue(base.contains("setSkipCollapsed(true)"));
        assertTrue(base.contains("setMaxWidth"));
        assertTrue(base.contains("R.dimen.sheet_max_width"));
        assertTrue(base.contains("SOFT_INPUT_ADJUST_RESIZE"));
    }

    @Test
    public void chatSheetsAreFragmentsRestoredThroughTheChildManager() throws Exception {
        String chat = read("com/nexachat/feature/chat/ChatFragment.java");
        for (String tag : new String[]{
                "MessageActionSheetFragment.TAG",
                "ForwardPickerSheet.TAG",
                "AttachmentPickerSheet.TAG"}) {
            assertTrue("ChatFragment must show " + tag + " via the child manager",
                    chat.contains(tag) && chat.contains("getChildFragmentManager()"));
        }
        assertFalse("raw bottom sheet dialogs are not rotation-safe",
                chat.contains("new BottomSheetDialog("));

        String views = read("com/nexachat/feature/chat/ChatInteractionsViews.java");
        assertFalse(views.contains("BottomSheetDialog"));
    }

    @Test
    public void groupAddMemberSheetIsARestoredFragment() throws Exception {
        String host = read("com/nexachat/feature/group/GroupDetailFragment.java");
        assertTrue(host.contains("AddMemberSheetFragment.TAG"));
        assertTrue(host.contains("getChildFragmentManager()"));
        assertFalse("raw bottom sheet dialogs are not rotation-safe",
                host.contains("new BottomSheetDialog("));

        String sheet = read("com/nexachat/feature/group/AddMemberSheetFragment.java");
        assertTrue(sheet.contains("extends NexaBottomSheetFragment"));
    }

    @Test
    public void migratedSheetsExtendTheSharedBase() throws Exception {
        assertTrue(read("com/nexachat/feature/chat/MessageActionSheetFragment.java")
                .contains("extends NexaBottomSheetFragment"));
        assertTrue(read("com/nexachat/feature/chat/ForwardPickerSheet.java")
                .contains("extends NexaBottomSheetFragment"));
        assertTrue(read("com/nexachat/feature/chat/AttachmentPickerSheet.java")
                .contains("extends NexaBottomSheetFragment"));
        assertTrue(read("com/nexachat/feature/reminder/ReminderEditorSheet.java")
                .contains("extends NexaBottomSheetFragment"));
        assertTrue(read("com/nexachat/feature/ask/AskBottomSheetFragment.java")
                .contains("NexaBottomSheetFragment"));
        assertTrue(read("com/nexachat/feature/summary/SummaryBottomSheetFragment.java")
                .contains("extends NexaBottomSheetFragment"));
        assertTrue(read("com/nexachat/feature/media/MediaPickerSheetFragment.java")
                .contains("extends NexaBottomSheetFragment"));
    }

    @Test
    public void actionRowsKeepTheScaffoldMetrics() throws Exception {
        Document row = parse(res("layout/item_message_action_row.xml"));
        assertEquals("@dimen/sheet_action_row_min_height",
                row.getDocumentElement().getAttributeNS(NS_ANDROID, "minHeight"));
        String dimens = new String(Files.readAllBytes(res("values/dimens_nexachat.xml").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(dimens.contains("sheet_action_row_min_height\">56dp"));
    }

    private static boolean hasHandleInclude(Document doc) {
        NodeList nodes = doc.getElementsByTagName("include");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if ("@layout/include_sheet_handle".equals(node.getAttribute("layout"))) {
                return true;
            }
        }
        return false;
    }

    private static File res(String relative) {
        File[] candidates = new File[]{
                new File("src/main/res", relative),
                new File("app/src/main/res", relative),
                new File("android/app/src/main/res", relative),
                new File("../app/src/main/res", relative)
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Missing res " + relative);
    }

    private static String read(String relative) throws Exception {
        Path[] candidates = new Path[]{
                Path.of("src/main/java", relative),
                Path.of("app/src/main/java", relative),
                Path.of("android/app/src/main/java", relative)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing " + relative);
    }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }
}
