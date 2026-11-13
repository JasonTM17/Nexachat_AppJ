package com.nexachat.feature.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class GroupAddMemberSheetScrollContractTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test
    public void memberListIsBoundedAndConfirmActionStaysOutsideScroller() throws Exception {
        Document document = loadSheet();
        Element list = findById(document, "addMemberList");
        Element confirm = findById(document, "addMemberConfirm");

        assertNull("member list must not be measured inside an unbounded NestedScrollView",
                ancestorNamed(list, "androidx.core.widget.NestedScrollView"));
        assertEquals("0dp", list.getAttributeNS(ANDROID_NS, "layout_height"));
        assertEquals("1", list.getAttributeNS(ANDROID_NS, "layout_weight"));
        assertNotNull("list and confirm action must share the sheet column", list.getParentNode());
        assertEquals(list.getParentNode(), confirm.getParentNode());
        assertEquals("wrap_content", confirm.getAttributeNS(ANDROID_NS, "layout_height"));
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

    private static Element ancestorNamed(Element element, String name) {
        for (Node parent = element.getParentNode(); parent instanceof Element; parent = parent.getParentNode()) {
            if (name.equals(parent.getNodeName())) {
                return (Element) parent;
            }
        }
        return null;
    }
}
