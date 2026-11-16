package com.nexachat.feature.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
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
 * The group header block reads like the design: a large avatar with an
 * admin-only edit badge, the group name in the headline style without a
 * forced weight, and a member-count subtitle instead of a section header
 * that repeats the count.
 */
public final class GroupDetailHeaderContractTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String APP_NS = "http://schemas.android.com/apk/res-auto";

    @Test
    public void headerShowsMemberCountUnderTheName() throws Exception {
        Element subtitle = findById(loadLayout(), "groupSubtitle");
        assertNotNull(subtitle);
        assertEquals("?attr/textAppearanceBodyMedium", attr(subtitle, "textAppearance"));
        assertEquals("?attr/colorOnSurfaceVariant", attr(subtitle, "textColor"));
        String source = readSource("GroupDetailFragment.java");
        assertTrue(source.contains("binding.groupSubtitle.setText("));
        assertTrue(source.contains("QuantityStrings.memberCount("));
        assertTrue(source.contains("R.string.group_members_label"));
    }

    @Test
    public void groupTitleKeepsTheThemeHeadlineStyle() throws Exception {
        Element title = findById(loadLayout(), "groupTitle");
        assertNotNull(title);
        assertNotEquals("bold", attr(title, "textStyle"));
    }

    @Test
    public void avatarEditBadgeIsDecorativeAndAdminOnly() throws Exception {
        Element badge = findById(loadLayout(), "avatarEditBadge");
        assertNotNull(badge);
        assertEquals("no", attr(badge, "importantForAccessibility"));
        assertEquals("gone", attr(badge, "visibility"));
        String source = readSource("GroupDetailFragment.java");
        assertTrue(source.contains("binding.avatarEditBadge.setVisibility("));
    }

    @Test
    public void memberRowDividerAlignsUnderTheName() throws Exception {
        Document row = loadResource("layout/item_group_member.xml");
        NodeList views = row.getElementsByTagName("View");
        Element divider = null;
        for (int i = 0; i < views.getLength(); i++) {
            Element candidate = (Element) views.item(i);
            if ("@dimen/divider_height".equals(attr(candidate, "layout_height"))) {
                divider = candidate;
            }
        }
        assertNotNull(divider);
        assertEquals("@dimen/divider_inset_compact", attr(divider, "layout_marginStart"));
    }

    private static Document loadLayout() throws Exception {
        return loadResource("layout/fragment_group_detail.xml");
    }

    private static Document loadResource(String relative) throws Exception {
        Path[] candidates = {
            Path.of("src/main/res", relative),
            Path.of("app/src/main/res", relative),
            Path.of("android/app/src/main/res", relative)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                return factory.newDocumentBuilder().parse(candidate.toFile());
            }
        }
        throw new AssertionError("Could not locate res/" + relative);
    }

    private static String readSource(String name) throws Exception {
        Path[] candidates = {
            Path.of("src/main/java/com/nexachat/feature/group", name),
            Path.of("app/src/main/java/com/nexachat/feature/group", name),
            Path.of("android/app/src/main/java/com/nexachat/feature/group", name),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
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
        return null;
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
