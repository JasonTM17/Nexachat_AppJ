package com.nexachat.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Safety list screens (blocked users, starred messages) follow the shared
 * state-view contract: a real empty state with icon and copy, an error state
 * with retry instead of a snackbar-only failure, a named progress indicator,
 * and row labels that name the person or message for screen readers. Also
 * pins the loading-label rule for the invite screens and the per-person
 * remove/unblock announcements.
 */
public final class SafetyListScreensUiContractTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

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
    public void safetyListScreensUseTheSharedStateViews() throws Exception {
        for (String layout : new String[] {
                "fragment_blocked_users.xml", "fragment_starred_messages.xml"}) {
            Document doc = parse(layout);
            Element empty = requireElement(doc, layout,
                    layout.equals("fragment_blocked_users.xml") ? "blockedEmpty" : "starredEmpty");
            assertEquals(layout + ": empty state must be the shared EmptyStateView",
                    "com.nexachat.core.ui.components.EmptyStateView", empty.getTagName());
            Element error = requireElement(doc, layout,
                    layout.equals("fragment_blocked_users.xml") ? "blockedError" : "starredError");
            assertEquals(layout + ": failure state must be the shared ErrorStateView",
                    "com.nexachat.core.ui.components.ErrorStateView", error.getTagName());
        }
    }

    @Test
    public void safetyListProgressIndicatorsUseTheSharedLoadingLabel() throws Exception {
        for (String layout : new String[] {
                "fragment_blocked_users.xml", "fragment_starred_messages.xml"}) {
            Document doc = parse(layout);
            String progressId = layout.equals("fragment_blocked_users.xml")
                    ? "blockedProgress" : "starredProgress";
            Element progress = requireElement(doc, layout, progressId);
            assertEquals(layout + ": progress must announce the shared loading label",
                    "@string/loading_status",
                    attr(progress, "contentDescription"));
        }
    }

    @Test
    public void blockedAndStarredRowsAnnounceTheirContent() throws Exception {
        String blocked = readSource("feature/safety/BlockedUserAdapter.java")
                .replaceAll("\\s+", " ");
        assertTrue("BlockedUserAdapter must name each row for screen readers",
                blocked.contains("holder.itemView.setContentDescription(name)"));
        assertTrue("BlockedUserAdapter unblock label must name the person",
                blocked.contains("R.string.cd_unblock_user_named"));

        String starred = readSource("feature/safety/StarredMessageAdapter.java")
                .replaceAll("\\s+", " ");
        assertTrue("StarredMessageAdapter must announce sender and snippet per row",
                starred.contains("R.string.cd_starred_message_row"));
    }

    @Test
    public void groupRemoveMemberAnnouncesThePersonNotTheDialogCopy() throws Exception {
        String source = readSource("feature/group/adapter/GroupMemberAdapter.java")
                .replaceAll("\\s+", " ");
        assertTrue("GroupMemberAdapter remove label must name the member",
                source.contains("R.string.cd_remove_member_named"));
        assertTrue("GroupMemberAdapter must not reuse the dialog sentence as a label",
                !source.contains("R.string.group_remove_member_confirm"));
    }

    @Test
    public void inviteLoadingIndicatorsKeepTheSharedLoadingLabel() throws Exception {
        for (String sourceName : new String[] {
                "feature/invite/InviteManageFragment.java",
                "feature/invite/InviteJoinFragment.java"}) {
            String source = readSource(sourceName).replaceAll("\\s+", " ");
            assertTrue(sourceName + " must label its loading state with loading_status",
                    source.contains("R.string.loading_status"));
            assertTrue(sourceName + " must not overwrite the loading label with the"
                            + " voice status word",
                    !source.contains("R.string.voice_status_loading"));
        }
    }

    @Test
    public void starredRowStarIconIsDecorative() throws Exception {
        Document doc = parse("item_starred_message.xml");
        NodeList images = doc.getDocumentElement().getElementsByTagName("ImageView");
        boolean foundDecorativeStar = false;
        for (int i = 0; i < images.getLength(); i++) {
            Element image = (Element) images.item(i);
            if (!"@drawable/ic_star".equals(attr(image, "src"))) {
                continue;
            }
            assertEquals("the leading star is decorative: the row already announces"
                            + " sender and snippet",
                    "no", attr(image, "importantForAccessibility"));
            assertTrue("a decorative icon must not carry a content description",
                    attr(image, "contentDescription").isEmpty());
            foundDecorativeStar = true;
        }
        assertTrue("item_starred_message.xml must keep its leading star icon",
                foundDecorativeStar);
    }

    @Test
    public void newSafetyStringsExistInBothLocales() throws Exception {
        String[] keys = {
                "cd_unblock_user_named", "user_unblocked_with_name", "cd_starred_message_row",
                "cd_contact_row", "cd_contact_row_name_only"};
        Map<String, String> en = loadStrings(new File(resDir, "values/strings_safety.xml"));
        Map<String, String> vi = loadStrings(new File(resDir, "values-vi/strings_safety.xml"));
        for (String key : keys) {
            assertTrue("values/strings_safety.xml missing " + key, en.containsKey(key));
            assertTrue("values-vi/strings_safety.xml missing " + key, vi.containsKey(key));
            String enText = en.get(key);
            String viText = vi.get(key);
            assertTrue(key + " must not be blank in either locale",
                    !enText.trim().isEmpty() && !viText.trim().isEmpty());
        }
    }

    private Document parse(String layoutName) throws Exception {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        return builder.parse(new File(new File(resDir, "layout"), layoutName));
    }

    private Element requireElement(Document doc, String layout, String id) {
        Element found = findById(doc.getDocumentElement(), id);
        assertNotNull(layout + ": missing @id/" + id, found);
        return found;
    }

    private static Element findById(Element el, String id) {
        String value = attr(el, "id");
        if (("@+id/" + id).equals(value)) {
            return el;
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
                continue;
            }
            Element found = findById((Element) children.item(i), id);
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

    private static String readSource(String relative) throws Exception {
        Path[] candidates = new Path[] {
                Path.of("src/main/java/com/nexachat", relative),
                Path.of("app/src/main/java/com/nexachat", relative),
                Path.of("android/app/src/main/java/com/nexachat", relative),
        };
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing " + relative);
    }

    private static Map<String, String> loadStrings(File file) throws Exception {
        Map<String, String> map = new HashMap<>();
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = builder.parse(file);
        NodeList nodes = doc.getDocumentElement().getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            map.put(el.getAttribute("name"), el.getTextContent());
        }
        return map;
    }
}
