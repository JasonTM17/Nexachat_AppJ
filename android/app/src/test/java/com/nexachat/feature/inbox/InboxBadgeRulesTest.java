package com.nexachat.feature.inbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nexachat.data.ConversationSummary;
import com.nexachat.data.PeerPresence;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;

public final class InboxBadgeRulesTest {

    @Test
    public void formatUnreadCount_returnsEmptyWhenZeroOrNegative() {
        assertEquals("", InboxBadgeRules.formatUnreadCount(0));
        assertEquals("", InboxBadgeRules.formatUnreadCount(-3));
    }

    @Test
    public void formatUnreadCount_returnsExactValueUpTo99() {
        assertEquals("1", InboxBadgeRules.formatUnreadCount(1));
        assertEquals("42", InboxBadgeRules.formatUnreadCount(42));
        assertEquals("99", InboxBadgeRules.formatUnreadCount(99));
    }

    @Test
    public void formatUnreadCount_capsAt99PlusAbove99() {
        assertEquals("99+", InboxBadgeRules.formatUnreadCount(100));
        assertEquals("99+", InboxBadgeRules.formatUnreadCount(999));
    }

    @Test
    public void resolveOnlineStatus_doesNotFabricateOnlineForUnknownDirectPresence() {
        ConversationSummary directUnknown = summary("c1", false, false, false, null);
        assertNull(InboxBadgeRules.resolveOnlineStatus(directUnknown));

        ConversationSummary directOffline = summary(
                "c2", false, false, false, new PeerPresence(false, 1000L));
        assertEquals(Boolean.FALSE, InboxBadgeRules.resolveOnlineStatus(directOffline));

        ConversationSummary directOnline = summary(
                "c3", false, false, false, new PeerPresence(true, null));
        assertEquals(Boolean.TRUE, InboxBadgeRules.resolveOnlineStatus(directOnline));

        ConversationSummary groupOnline = summary(
                "c4", true, false, false, new PeerPresence(true, null));
        assertNull(InboxBadgeRules.resolveOnlineStatus(groupOnline));
    }

    @Test
    public void physicalSwipePreviewMatchesRelativeActionInBothLayoutDirections() {
        ConversationSummary normal = summary("c1", false, false, false, null);
        assertEquals(InboxBadgeRules.SwipeAction.PIN,
                InboxBadgeRules.resolvePhysicalSwipeAction(normal, 100f, false));
        assertEquals(InboxBadgeRules.SwipeAction.PIN,
                InboxBadgeRules.resolvePhysicalSwipeAction(normal, -100f, true));
        assertEquals(InboxBadgeRules.SwipeAction.ARCHIVE,
                InboxBadgeRules.resolvePhysicalSwipeAction(normal, -100f, false));
        assertEquals(InboxBadgeRules.SwipeAction.ARCHIVE,
                InboxBadgeRules.resolvePhysicalSwipeAction(normal, 100f, true));
        ConversationSummary changed = summary("c2", false, true, true, null);
        assertEquals(InboxBadgeRules.SwipeAction.UNPIN,
                InboxBadgeRules.resolvePhysicalSwipeAction(changed, -100f, true));
        assertEquals(InboxBadgeRules.SwipeAction.UNARCHIVE,
                InboxBadgeRules.resolvePhysicalSwipeAction(changed, 100f, true));
        assertEquals(InboxBadgeRules.SwipeAction.NONE,
                InboxBadgeRules.resolvePhysicalSwipeAction(normal, 0f, true));
        assertEquals(InboxBadgeRules.SwipeAction.NONE,
                InboxBadgeRules.resolvePhysicalSwipeAction(null, 100f, false));
        assertNull(InboxBadgeRules.resolveOnlineStatus(null));
    }

    @Test
    public void conversationListAdapter_appendsPresenceStringsToTitleContentDescription() throws Exception {
        String source = readAdapterSource();
        assertTrue(source.contains("R.string.cd_online_status"));
        assertTrue(source.contains("R.string.presence_offline"));
        assertTrue(source.contains("setContentDescription"));
        assertTrue(source.contains("resolveOnlineStatus"));
    }

    @Test
    public void activeNowPeerLayout_hidesRedundantAvatarAndCaptionFromAccessibility() throws Exception {
        File layout = locateResFile("layout/item_active_now_peer.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(layout);
        Element avatar = findElementByAndroidId(document.getDocumentElement(), "activePeerAvatar");
        Element name = findElementByAndroidId(document.getDocumentElement(), "activePeerName");
        assertNotNull(avatar);
        assertNotNull(name);
        assertEquals("no", androidAttr(avatar, "importantForAccessibility"));
        assertEquals("no", androidAttr(name, "importantForAccessibility"));
    }

    @Test
    public void avatarView_hidesOfflinePresenceDotAndUsesPresenceRules() throws Exception {
        String source = readAvatarViewSource();
        assertTrue(source.contains("AvatarPresenceDotRules"));
        assertTrue(source.contains("shouldShowDot"));
        assertFalse("offline peers should not render a grey dot",
                source.contains("colorOnSurfaceVariant"));
        assertFalse(source.contains("stitch_offline_dot"));
    }

    @Test
    public void resolveSwipeActions_reflectPinnedAndArchivedState() {
        ConversationSummary normal = summary("c1", false, false, false, null);
        assertEquals(InboxBadgeRules.SwipeAction.PIN, InboxBadgeRules.resolveStartSwipeAction(normal));
        assertEquals(InboxBadgeRules.SwipeAction.ARCHIVE, InboxBadgeRules.resolveEndSwipeAction(normal));

        ConversationSummary pinnedAndArchived = summary("c2", false, true, true, null);
        assertEquals(
                InboxBadgeRules.SwipeAction.UNPIN,
                InboxBadgeRules.resolveStartSwipeAction(pinnedAndArchived));
        assertEquals(
                InboxBadgeRules.SwipeAction.UNARCHIVE,
                InboxBadgeRules.resolveEndSwipeAction(pinnedAndArchived));
    }

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static String readAdapterSource() throws Exception {
        File javaDir = locateJavaDir();
        File adapter = new File(
                javaDir,
                "com/nexachat/feature/inbox/adapter/ConversationListAdapter.java");
        assertTrue(adapter.isFile());
        return readUtf8(adapter);
    }

    private static String readAvatarViewSource() throws Exception {
        File javaDir = locateJavaDir();
        File avatar = new File(javaDir, "com/nexachat/core/ui/components/AvatarView.java");
        assertTrue(avatar.isFile());
        return readUtf8(avatar);
    }

    private static String readUtf8(File file) throws Exception {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
            }
        }
        return body.toString();
    }

    private static File locateJavaDir() {
        File[] candidates = {
                new File("src/main/java"),
                new File("app/src/main/java"),
                new File("android/app/src/main/java"),
                new File("../app/src/main/java")
        };
        for (File candidate : candidates) {
            if (new File(candidate, "com/nexachat/feature/inbox/InboxBadgeRules.java").isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate Java sources");
    }

    private static File locateResFile(String relative) {
        File[] candidates = {
                new File("src/main/res/" + relative),
                new File("app/src/main/res/" + relative),
                new File("android/app/src/main/res/" + relative),
                new File("../app/src/main/res/" + relative)
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate " + relative);
    }

    private static Element findElementByAndroidId(Element root, String id) {
        String rawId = root.getAttributeNS(ANDROID_NS, "id");
        if (rawId.isEmpty()) {
            rawId = root.getAttribute("android:id");
        }
        if (id.equals(stripId(rawId))) {
            return root;
        }
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                Element found = findElementByAndroidId(element, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String androidAttr(Element element, String name) {
        String value = element.getAttributeNS(ANDROID_NS, name);
        if (value.isEmpty()) {
            value = element.getAttribute("android:" + name);
        }
        return value;
    }

    private static String stripId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String value = raw;
        if (value.startsWith("@+id/") || value.startsWith("@id/")) {
            value = value.substring(value.indexOf('/') + 1);
        }
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static ConversationSummary summary(
            String id,
            boolean group,
            boolean pinned,
            boolean archived,
            PeerPresence presence
    ) {
        return new ConversationSummary(
                id,
                group ? "GROUP" : "DIRECT",
                group ? "Group " + id : null,
                "Hello",
                "Peer " + id,
                1_700_000_000_000L,
                1L,
                0,
                "Peer " + id,
                null,
                null,
                0L,
                0L,
                null,
                "u1",
                false,
                presence,
                false,
                null,
                archived,
                false,
                pinned,
                pinned ? 1_700_000_000_000L : null
        );
    }
}
