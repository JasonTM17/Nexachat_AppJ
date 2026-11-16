package com.nexachat.feature.invite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Invite surfaces use one filled primary per card; secondary actions are
 * tonal/text NexaChat buttons and destructive actions sit on the error
 * container. Section titles come from the shared SectionHeaderView.
 */
public final class InviteActionsContractTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String APP_NS = "http://schemas.android.com/apk/res-auto";

    @Test
    public void manageScreenUsesNexaChatButtonsAndSectionHeader() throws Exception {
        Document document = loadLayout("layout/fragment_invite_manage.xml");
        Element root = document.getDocumentElement();
        for (String id : new String[] {"expiryButton", "maxUsesButton", "copyButton", "shareButton"}) {
            Element button = findById(root, id);
            assertNotNull(id, button);
            assertEquals(id + " style",
                    "@style/Widget.NexaChat.Button.Tonal", attr(button, "style"));
        }
        Element refresh = findById(root, "refreshRequestsButton");
        assertNotNull(refresh);
        assertEquals("@style/Widget.NexaChat.Button.Text", attr(refresh, "style"));

        Element revoke = findById(root, "revokeButton");
        assertNotNull(revoke);
        assertEquals("?attr/colorErrorContainer", attr(revoke, "backgroundTint"));
        assertEquals("?attr/colorOnErrorContainer", attr(revoke, "textColor"));

        Element header = findById(root, "requestsHeader");
        assertNotNull(header);
        assertEquals("com.nexachat.core.ui.components.SectionHeaderView",
                header.getTagName());
        assertTrue("invite_manage_hint copy stays out of the layout",
                !readResource("layout/fragment_invite_manage.xml")
                        .contains("invite_manage_hint"));
    }

    @Test
    public void joinScreenSecondariesAreTonal() throws Exception {
        Element root = loadLayout("layout/fragment_invite_join.xml").getDocumentElement();
        for (String id : new String[] {"checkStatusButton", "retryButton", "signInButton"}) {
            Element button = findById(root, id);
            assertNotNull(id, button);
            assertEquals(id + " style",
                    "@style/Widget.NexaChat.Button.Tonal", attr(button, "style"));
        }
        for (String id : new String[] {"joinButton", "openChatButton"}) {
            Element button = findById(root, id);
            assertNotNull(id, button);
            assertEquals(id + " keeps the filled theme default",
                    "", attr(button, "style"));
        }
        Element title = findById(root, "groupTitle");
        assertNotNull(title);
        assertNotEquals("bold", attr(title, "textStyle"));
    }

    @Test
    public void joinRequestRejectIsATextAction() throws Exception {
        Element root = loadLayout("layout/item_invite_join_request.xml").getDocumentElement();
        Element reject = findById(root, "rejectButton");
        assertNotNull(reject);
        assertEquals("@style/Widget.NexaChat.Button.Text", attr(reject, "style"));
        Element approve = findById(root, "approveButton");
        assertNotNull(approve);
        assertEquals("approve keeps the filled theme default",
                "", attr(approve, "style"));
    }

    private static Document loadLayout(String relative) throws Exception {
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

    private static String readResource(String relative) throws Exception {
        Path[] candidates = {
            Path.of("src/main/res", relative),
            Path.of("app/src/main/res", relative),
            Path.of("android/app/src/main/res", relative)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Could not locate res/" + relative);
    }

    private static Element findById(Element root, String id) {
        NodeList elements = root.getElementsByTagName("*");
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
        if (value.isEmpty()) {
            value = element.getAttribute(localName);
        }
        return value;
    }
}
