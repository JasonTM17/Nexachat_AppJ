package com.nexachat.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Contract tests for M4 Tonal Plum contrast fixes, compose media-remove tokens,
 * and landscape call layout parity.
 */
public final class TonalPlumAndFeedA11yContractTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String NS_APP = "http://schemas.android.com/apk/res-auto";

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
    public void filledButtonStyleSetsIconTintToFilledButtonText() throws Exception {
        Document styles = parse(new File(resDir, "values/styles_nexachat.xml"));
        NodeList list = styles.getElementsByTagName("style");
        Element filledButtonStyle = null;
        for (int i = 0; i < list.getLength(); i++) {
            Element s = (Element) list.item(i);
            if ("Widget.NexaChat.Button".equals(s.getAttribute("name"))) {
                filledButtonStyle = s;
                break;
            }
        }
        assertTrue("Widget.NexaChat.Button must exist", filledButtonStyle != null);
        String iconTint = null;
        NodeList items = filledButtonStyle.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if ("iconTint".equals(item.getAttribute("name"))) {
                iconTint = item.getTextContent().trim();
            }
        }
        assertEquals("@color/nexa_filled_button_text", iconTint);

        String[] authLayouts = {
                "layout/fragment_login.xml",
                "layout/fragment_register.xml",
                "layout/fragment_complete_profile.xml"
        };
        for (String layout : authLayouts) {
            String xml = new String(
                    Files.readAllBytes(new File(resDir, layout).toPath()),
                    StandardCharsets.UTF_8);
            assertTrue(layout + " must not override iconTint with ?attr/colorOnPrimary",
                    !xml.contains("app:iconTint=\"?attr/colorOnPrimary\""));
        }
    }

    @Test
    public void composeMediaRemoveButtonsUseNeutralDarkMediaTokens() throws Exception {
        Document doc = parse(new File(resDir, "layout/fragment_post_compose.xml"));
        for (String id : new String[] {"composeImageRemove", "composeVideoRemove"}) {
            Element button = findById(doc.getDocumentElement(), id);
            assertTrue("Missing " + id, button != null);
            assertEquals("@drawable/bg_media_play_button", button.getAttributeNS(NS_ANDROID, "background"));
            assertEquals("@color/nexa_on_media_meta", button.getAttributeNS(NS_APP, "tint"));
        }
    }

    @Test
    public void landscapeCallActivityUsesThemeTextAppearancesAndBottomMargin() throws Exception {
        Document doc = parse(new File(resDir, "layout-land/activity_call.xml"));
        for (String id : new String[] {"call_peer_initials", "call_peer_name", "call_status", "call_timer"}) {
            Element tv = findById(doc.getDocumentElement(), id);
            assertTrue("Missing " + id, tv != null);
            assertTrue(id + " must not hardcode textSize", tv.getAttributeNS(NS_ANDROID, "textSize").isEmpty());
            assertTrue(id + " must use ?attr/textAppearance*",
                    tv.getAttributeNS(NS_ANDROID, "textAppearance").startsWith("?attr/textAppearance"));
        }
        NodeList includes = doc.getElementsByTagName("include");
        Element controlsInclude = null;
        for (int i = 0; i < includes.getLength(); i++) {
            Element inc = (Element) includes.item(i);
            if ("@layout/view_call_controls".equals(inc.getAttribute("layout"))) {
                controlsInclude = inc;
                break;
            }
        }
        assertTrue("Missing view_call_controls include", controlsInclude != null);
        assertEquals("24dp", controlsInclude.getAttributeNS(NS_ANDROID, "layout_marginBottom"));
    }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }

    private static Element findById(Node node, String idSuffix) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) node;
            String id = el.getAttributeNS(NS_ANDROID, "id");
            if (("@+id/" + idSuffix).equals(id)) {
                return el;
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Element match = findById(children.item(i), idSuffix);
            if (match != null) {
                return match;
            }
        }
        return null;
    }
}
