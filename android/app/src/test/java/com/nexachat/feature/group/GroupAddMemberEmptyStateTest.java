package com.nexachat.feature.group;

import static org.junit.Assert.assertEquals;
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
 * When every contact is already a member, the add-member sheet must explain
 * the empty list and offer the invite link instead of a dead confirm button.
 */
public final class GroupAddMemberEmptyStateTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test
    public void sheetDeclaresHiddenEmptyStateAndInviteShortcut() throws Exception {
        Document sheet = loadSheet();

        Element empty = findById(sheet, "addMemberEmpty");
        assertEquals("@string/group_add_member_empty", empty.getAttributeNS(ANDROID_NS, "text"));
        assertEquals("gone", empty.getAttributeNS(ANDROID_NS, "visibility"));
        assertEquals("polite", empty.getAttributeNS(ANDROID_NS, "accessibilityLiveRegion"));

        Element invite = findById(sheet, "addMemberInviteLink");
        assertEquals("@string/invite_manage_action", invite.getAttributeNS(ANDROID_NS, "text"));
        assertEquals("gone", invite.getAttributeNS(ANDROID_NS, "visibility"));
    }

    @Test
    public void emptyStateOccupiesTheListRegion() throws Exception {
        Document sheet = loadSheet();
        Element emptyState = findById(sheet, "addMemberEmptyState");
        Element list = findById(sheet, "addMemberList");

        assertEquals("0dp", emptyState.getAttributeNS(ANDROID_NS, "layout_height"));
        assertEquals("1", emptyState.getAttributeNS(ANDROID_NS, "layout_weight"));
        assertEquals("gone", emptyState.getAttributeNS(ANDROID_NS, "visibility"));
        assertEquals(list.getParentNode(), emptyState.getParentNode());
    }

    @Test
    public void fragmentSwapsTheConfirmForTheEmptyState() throws Exception {
        String source = new String(Files.readAllBytes(locateSource()), StandardCharsets.UTF_8);
        assertTrue(source.contains("getAddableContacts().isEmpty()"));
        assertTrue(source.contains("addMemberEmpty.setVisibility"));
        assertTrue(source.contains("addMemberInviteLink.setVisibility"));
        assertTrue(source.contains("addMemberConfirm.setVisibility"));
        assertTrue(source.contains("addMemberInviteLink.setOnClickListener"));
    }

    private static Document loadSheet() throws Exception {
        Path[] candidates = {
            Path.of("src/main/res/layout/bottom_sheet_group_add_member.xml"),
            Path.of("app/src/main/res/layout/bottom_sheet_group_add_member.xml"),
            Path.of("android/app/src/main/res/layout/bottom_sheet_group_add_member.xml")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                return factory.newDocumentBuilder().parse(candidate.toFile());
            }
        }
        throw new AssertionError("Could not locate bottom_sheet_group_add_member.xml");
    }

    private static Path locateSource() {
        String name = "AddMemberSheetFragment.java";
        Path[] candidates = new Path[] {
                Path.of("src/main/java/com/nexachat/feature/group", name),
                Path.of("app/src/main/java/com/nexachat/feature/group", name),
                Path.of("android/app/src/main/java/com/nexachat/feature/group", name),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Missing " + name);
    }

    private static Element findById(Document document, String id) {
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (element.getAttributeNS(ANDROID_NS, "id").endsWith("/" + id)) {
                return element;
            }
        }
        throw new AssertionError("Missing view id " + id);
    }
}
