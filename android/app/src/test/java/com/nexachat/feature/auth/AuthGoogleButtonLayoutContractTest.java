package com.nexachat.feature.auth;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import static org.junit.Assert.assertEquals;

/** Layout contract for the primary Google button on Login and Register. */
public final class AuthGoogleButtonLayoutContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private File resDir;

    @Before
    public void setUp() {
        File nested = new File("app/src/main/res");
        if (new File(nested, "values/colors_nexachat.xml").isFile()) {
            resDir = nested;
            return;
        }
        File top = new File("src/main/res");
        if (new File(top, "values/colors_nexachat.xml").isFile()) {
            resDir = top;
            return;
        }
        File worktree = new File("android/app/src/main/res");
        if (new File(worktree, "values/colors_nexachat.xml").isFile()) {
            resDir = worktree;
            return;
        }
        throw new IllegalStateException("Could not locate Android resources");
    }

    @Test
    public void loginAndRegisterExposeAccessibleGoogleButtons() throws Exception {
        assertGoogleButton("layout/fragment_login.xml");
        assertGoogleButton("layout/fragment_register.xml");
    }

    @Test
    public void googleButtonStyleCarriesTheTonalDefaults() throws Exception {
        Element style = googleStyle();
        assertEquals("@dimen/min_touch_target", styleItem(style, "android:minHeight"));
        assertEquals("@color/colorSurfaceContainerLowest",
                styleItem(style, "backgroundTint"));
        assertEquals("@color/colorOutlineVariant", styleItem(style, "strokeColor"));
        assertEquals("@dimen/control_stroke_width", styleItem(style, "strokeWidth"));
    }

    private void assertGoogleButton(String layout) throws Exception {
        Element button = findById(layout, "googleSignIn");
        assertEquals("@style/Widget.NexaChat.Button.Google", button.getAttribute("style"));
        assertEquals("@string/action_continue_with_google", attr(button, "text"));
        assertEquals("@string/cd_continue_with_google", attr(button, "contentDescription"));
        // The 48dp touch target and the lowest-surface hairline card are style
        // defaults now; per-layout overrides stay out of the layouts.
        assertEquals("", attr(button, "minHeight"));
    }

    private Element googleStyle() throws Exception {
        NodeList styles = parse("values/styles_nexachat.xml")
                .getElementsByTagName("style");
        for (int i = 0; i < styles.getLength(); i++) {
            Element style = (Element) styles.item(i);
            if ("Widget.NexaChat.Button.Google".equals(style.getAttribute("name"))) {
                return style;
            }
        }
        throw new AssertionError("Widget.NexaChat.Button.Google not declared");
    }

    private static String styleItem(Element style, String name) {
        NodeList items = style.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if (name.equals(item.getAttribute("name"))) {
                return item.getTextContent().trim();
            }
        }
        return "";
    }

    private org.w3c.dom.Document parse(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(resDir, path));
    }

    private Element findById(String layout, String id) throws Exception {
        NodeList nodes = parse(layout).getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            String androidId = node.getAttributeNS(ANDROID, "id");
            if (androidId == null || androidId.isEmpty()) {
                androidId = node.getAttribute("android:id");
            }
            if (("@+id/" + id).equals(androidId)) {
                return node;
            }
        }
        throw new AssertionError("Missing " + id + " in " + layout);
    }

    private static String attr(Element node, String name) {
        String value = node.getAttributeNS(ANDROID, name);
        if (value == null || value.isEmpty()) {
            value = node.getAttribute("android:" + name);
        }
        return value;
    }
}
