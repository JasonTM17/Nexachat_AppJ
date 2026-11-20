package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Second-pass view-layer accessibility contract for the screens outside the message
 * bubble: decorative brand marks stay out of the accessibility tree, every progress
 * indicator shares one loading label, a clickable container does not repeat its own
 * label through a child, and a button keeps its visible text as the announced name.
 */
public final class ViewLayerAccessibilityContractTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

    /** Auth screens that render the shared {@code @id/authLogo} brand mark. */
    private static final String[] AUTH_LAYOUTS = {
            "fragment_login.xml",
            "fragment_register.xml",
            "fragment_complete_profile.xml",
            "fragment_splash.xml",
            "fragment_forgot_password.xml"
    };

    /** Clickable search pills whose label lives on the container, not on the child text. */
    private static final String[][] SEARCH_PILLS = {
            {"fragment_contacts.xml", "searchPill"},
            {"fragment_inbox.xml", "inboxSearchPill"}
    };

    /**
     * Progress indicators whose content description is assigned at bind time in
     * MessageBubbleBinder (sending spinner, translation spinner); the XML stays
     * label-free so the binder owns the contextual label.
     */
    private static final String[][] CODE_LABELED_PROGRESS = {
            {"include_message_bubble_meta.xml", "sendingProgress"},
            {"include_message_translation.xml", "translationProgress"}
    };

    private File resDir;

    @Before
    public void setUp() {
        resDir = locateResDir();
    }

    private static File locateResDir() {
        File[] candidates = new File[] {
                new File("src/main/res"),
                new File("app/src/main/res"),
                new File("android/app/src/main/res"),
                new File("../app/src/main/res")
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory() && new File(candidate, "values/colors_nexachat.xml").isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate res directory from " + new File(".").getAbsolutePath());
    }

    /**
     * The brand mark is decoration: the screen title already names the app, so exposing
     * the logo adds a focus stop that announces the product name a second time.
     */
    @Test
    public void authLogoStaysOutOfTheAccessibilityTree() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String layout : AUTH_LAYOUTS) {
            Element logo = findById(parse(layout), "authLogo");
            if (logo == null) {
                continue;
            }
            String importance = attr(logo, "importantForAccessibility");
            if (!"no".equals(importance)) {
                violations.add(layout + ": authLogo importantForAccessibility is \"" + importance
                        + "\", expected \"no\"");
            }
            String description = attr(logo, "contentDescription");
            if (!description.isEmpty()) {
                violations.add(layout + ": authLogo still carries contentDescription \""
                        + description + "\"");
            }
        }
        if (!violations.isEmpty()) {
            fail("Auth brand mark accessibility:\n" + String.join("\n", violations));
        }
    }

    /**
     * Every indeterminate progress indicator that names itself must use the shared
     * loading label, so the announcement does not depend on which screen is busy.
     */
    @Test
    public void progressIndicatorsUseTheSharedLoadingLabel() throws Exception {
        List<String> violations = new ArrayList<>();
        scanProgressIndicators(new File(resDir, "layout"), violations);
        File layoutLand = new File(resDir, "layout-land");
        if (layoutLand.isDirectory()) {
            scanProgressIndicators(layoutLand, violations);
        }
        if (!violations.isEmpty()) {
            fail("Progress indicator labels:\n" + String.join("\n", violations));
        }
    }

    private void scanProgressIndicators(File dir, List<String> violations) throws Exception {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            Document doc = parse(file);
            NodeList elements = doc.getElementsByTagName("*");
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                if (!element.getTagName().endsWith("ProgressIndicator")) {
                    continue;
                }
                String description = attr(element, "contentDescription");
                if (description.isEmpty()) {
                    continue;
                }
                if (!"@string/loading_status".equals(description)) {
                    violations.add(file.getName() + ": " + element.getTagName()
                            + " contentDescription is \"" + description
                            + "\", expected @string/loading_status");
                }
            }
        }
    }

    /**
     * A progress indicator is visible to accessibility services by default, so it must
     * either name itself (contentDescription) or be explicitly decorative. The only
     * exceptions are the indicators whose label the binder assigns per state.
     */
    @Test
    public void progressIndicatorsAreAnnouncedOrDecorative() throws Exception {
        List<String> violations = new ArrayList<>();
        scanProgressIndicatorsForLabels(new File(resDir, "layout"), violations);
        File layoutLand = new File(resDir, "layout-land");
        if (layoutLand.isDirectory()) {
            scanProgressIndicatorsForLabels(layoutLand, violations);
        }
        if (!violations.isEmpty()) {
            fail("Progress indicators missing from the accessibility tree without a label:\n"
                    + String.join("\n", violations));
        }
    }

    private void scanProgressIndicatorsForLabels(File dir, List<String> violations) throws Exception {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            Document doc = parse(file);
            NodeList elements = doc.getElementsByTagName("*");
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                String tag = element.getTagName();
                if (!tag.endsWith("ProgressIndicator") && !"ProgressBar".equals(tag)) {
                    continue;
                }
                String id = attr(element, "id");
                if (isCodeLabeled(file.getName(), id)) {
                    continue;
                }
                String importance = attr(element, "importantForAccessibility");
                String description = attr(element, "contentDescription");
                if (!"no".equals(importance) && description.isEmpty()) {
                    violations.add(file.getName() + ": " + tag + " " + id
                            + " is neither labeled nor importantForAccessibility=\"no\"");
                }
            }
        }
    }

    private static boolean isCodeLabeled(String layout, String id) {
        String bareId = id.replace("@+id/", "").replace("@id/", "");
        for (String[] entry : CODE_LABELED_PROGRESS) {
            if (entry[0].equals(layout) && entry[1].equals(bareId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The pill is the clickable node and carries the label; the child text repeats the
     * same words, so leaving it in the tree makes screen readers stop twice on one control.
     */
    @Test
    public void searchPillsDoNotRepeatTheirLabel() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String[] pill : SEARCH_PILLS) {
            String layout = pill[0];
            Element container = findById(parse(layout), pill[1]);
            if (container == null) {
                violations.add(layout + ": missing @id/" + pill[1]);
                continue;
            }
            if (attr(container, "contentDescription").isEmpty()) {
                violations.add(layout + ": " + pill[1] + " has no contentDescription to label the pill");
            }
            for (Element text : descendantsWithText(container)) {
                if (!"no".equals(attr(text, "importantForAccessibility"))) {
                    violations.add(layout + ": " + pill[1] + " child text \""
                            + attr(text, "text") + "\" repeats the pill label and must be"
                            + " importantForAccessibility=\"no\"");
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("Search pill accessibility:\n" + String.join("\n", violations));
        }
    }

    /**
     * A button that already shows text must not override it: the announced name has to
     * contain the visible label, otherwise voice control cannot target the button.
     */
    @Test
    public void askSendButtonKeepsItsVisibleLabel() throws Exception {
        Element send = findById(parse("bottom_sheet_ask_chat.xml"), "askSend");
        assertNotNull("bottom_sheet_ask_chat.xml: missing @id/askSend", send);
        assertEquals("askSend visible label", "@string/ask_send", attr(send, "text"));
        String description = attr(send, "contentDescription");
        if (!description.isEmpty()) {
            fail("askSend overrides its visible label with contentDescription \"" + description
                    + "\"; the announced name must contain the visible text");
        }
    }

    /**
     * The comment row is not clickable, but CommentAdapter puts the full
     * "author: body" label on the row container, which makes it a focus stop.
     * The child texts repeat those words, so they must stay out of the tree or
     * a screen reader reads every comment twice.
     */
    @Test
    public void commentRowChildrenDoNotRepeatTheRowLabel() throws Exception {
        String[] mutedChildren = {"commentAuthor", "commentBody", "commentTime"};
        Element row = parse("item_feed_comment.xml").getDocumentElement();
        List<String> violations = new ArrayList<>();
        for (String child : mutedChildren) {
            Element element = findById(row, child);
            if (element == null) {
                violations.add("item_feed_comment.xml: missing @id/" + child);
                continue;
            }
            String importance = attr(element, "importantForAccessibility");
            if (!"no".equals(importance)) {
                violations.add("item_feed_comment.xml: " + child
                        + " importantForAccessibility is \"" + importance
                        + "\", expected \"no\" (the row carries the comment label)");
            }
        }
        if (!violations.isEmpty()) {
            fail("Feed comment row accessibility:\n" + String.join("\n", violations));
        }
    }

    /**
     * The unread-summary banner text is swapped asynchronously as messages arrive;
     * without a live region a screen reader never announces the new count.
     */
    @Test
    public void unreadBannerAnnouncesCountChanges() throws Exception {
        Element text = findById(parse("include_unread_banner.xml"), "unreadBannerText");
        assertNotNull("include_unread_banner.xml: missing @id/unreadBannerText", text);
        assertEquals("unreadBannerText live region", "polite",
                attr(text, "accessibilityLiveRegion"));
    }

    /**
     * The group name is the only place the screen states which group it is, so the
     * admin-only rename affordance must not replace it in the announced name.
     */
    @Test
    public void groupTitleKeepsTheGroupNameForScreenReaders() throws Exception {
        String source = readSource("feature/group/GroupDetailFragment.java")
                .replaceAll("\\s+", " ");
        String marker = "binding.groupTitle.setContentDescription(";
        int start = source.indexOf(marker);
        assertTrue("GroupDetailFragment must set the group title content description",
                start >= 0);
        int end = source.indexOf(");", start);
        assertTrue("groupTitle contentDescription must be a single statement", end > start);
        String assignment = source.substring(start, end);
        assertTrue("groupTitle contentDescription must keep the group name: " + assignment,
                assignment.contains("title"));
        assertTrue("groupTitle contentDescription must still name the rename action: " + assignment,
                assignment.contains("R.string.action_rename_group"));
    }

    private static List<Element> descendantsWithText(Element root) {
        List<Element> found = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) child;
            if (!attr(element, "text").isEmpty()) {
                found.add(element);
            }
            found.addAll(descendantsWithText(element));
        }
        return found;
    }

    private Document parse(String layoutName) throws Exception {
        return parse(new File(new File(resDir, "layout"), layoutName));
    }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    private static Element findById(Document doc, String id) {
        return findById(doc.getDocumentElement(), id);
    }

    private static Element findById(Node node, String id) {
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            return null;
        }
        Element el = (Element) node;
        if (("@+id/" + id).equals(attr(el, "id"))) {
            return el;
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Element found = findById(children.item(i), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String readSource(String relative) throws Exception {
        File[] candidates = new File[] {
                new File("src/main/java/com/nexachat", relative),
                new File("app/src/main/java/com/nexachat", relative),
                new File("android/app/src/main/java/com/nexachat", relative)
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Could not locate source " + relative);
    }

    private static String attr(Element el, String localName) {
        String value = el.getAttributeNS(NS_ANDROID, localName);
        if (value == null || value.isEmpty()) {
            value = el.getAttribute("android:" + localName);
        }
        return value == null ? "" : value;
    }
}
