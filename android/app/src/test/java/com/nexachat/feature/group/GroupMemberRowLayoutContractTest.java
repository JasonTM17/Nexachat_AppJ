package com.nexachat.feature.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Member rows read as a calm list: names keep the default weight and the
 * remove affordance stays neutral so red only marks real destructive actions.
 */
public final class GroupMemberRowLayoutContractTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String APP_NS = "http://schemas.android.com/apk/res-auto";

    @Test
    public void removeMemberIconUsesNeutralTint() throws Exception {
        Element remove = findById(loadRow(), "removeMember");
        String tint = attr(remove, "tint");
        assertEquals("?attr/colorOnSurfaceVariant", tint);
        assertNotEquals("?attr/colorError", tint);
    }

    @Test
    public void memberNameIsNotForcedBold() throws Exception {
        Element name = findById(loadRow(), "memberName");
        assertNotEquals("bold", attr(name, "textStyle"));
    }

    private static Document loadRow() throws Exception {
        Path[] candidates = {
            Path.of("src/main/res/layout/item_group_member.xml"),
            Path.of("app/src/main/res/layout/item_group_member.xml"),
            Path.of("android/app/src/main/res/layout/item_group_member.xml")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                return factory.newDocumentBuilder().parse(candidate.toFile());
            }
        }
        throw new AssertionError("Could not locate item_group_member.xml");
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

    private static String attr(Element element, String localName) {
        String value = element.getAttributeNS(ANDROID_NS, localName);
        if (value.isEmpty()) {
            value = element.getAttributeNS(APP_NS, localName);
        }
        if (value.isEmpty()) {
            value = element.getAttribute("android:" + localName);
        }
        if (value.isEmpty()) {
            value = element.getAttribute("app:" + localName);
        }
        return value;
    }
}
