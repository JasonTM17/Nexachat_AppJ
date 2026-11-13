package com.nexachat.feature.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

/** Layout contract for profile theme control and header alignment. */
public final class ProfileThemeLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    @Test
    public void toolbarUsesNexaTitleLargeAppearance() throws Exception {
        Element toolbar = requireId("fragment_profile.xml", "profileToolbar");
        String appearance = toolbar.getAttributeNS(APP, "titleTextAppearance");
        assertEquals("?attr/textAppearanceTitleLarge", appearance);
    }

    @Test
    public void heroNameIsCenteredWhenWrapped() throws Exception {
        Element name = requireId("fragment_profile.xml", "profileName");
        assertEquals("match_parent", name.getAttributeNS(ANDROID, "layout_width"));
        assertEquals("center", name.getAttributeNS(ANDROID, "gravity"));
    }

    @Test
    public void themeChipsUseCardHorizontalInsetNotRowIndent() throws Exception {
        Element chips = requireId("fragment_profile.xml", "themeQuickChips");
        String marginStart = chips.getAttributeNS(ANDROID, "layout_marginStart");
        assertTrue(
                "Chips should not use the 72dp settings-row indent",
                marginStart.isEmpty() || !marginStart.contains("72"));
        assertEquals("16dp", chips.getAttributeNS(ANDROID, "layout_marginHorizontal"));
    }

    private static Element requireId(String file, String id) throws Exception {
        Element element = view(file, id);
        assertNotNull(element);
        return element;
    }

    private static Element view(String file, String id) throws Exception {
        Document document = parse("layout/" + file);
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) {
                return node;
            }
        }
        throw new AssertionError("Missing " + id + " in " + file);
    }

    private static Document parse(String relative) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(Path.of("src/main/res", relative).toFile());
    }
}
