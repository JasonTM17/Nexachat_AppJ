package com.nexachat.feature.auth;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
        throw new IllegalStateException("Could not locate Android resources");
    }

    @Test
    public void loginAndRegisterExposeAccessibleGoogleButtons() throws Exception {
        assertGoogleButton("layout/fragment_login.xml");
        assertGoogleButton("layout/fragment_register.xml");
    }

    private void assertGoogleButton(String layout) throws Exception {
        Element button = find("@" + layout.replace("layout/", "") + "/googleSignIn", layout);
        // fallback: find by id suffix
        button = findById(layout, "googleSignIn");
        assertEquals("@string/action_continue_with_google", attr(button, "text"));
        assertEquals("@string/cd_continue_with_google", attr(button, "contentDescription"));
        String minHeight = attr(button, "minHeight");
        assertTrue(layout + " minHeight", "56dp".equals(minHeight) || "54dp".equals(minHeight));
        int minHeightDp = Integer.parseInt(minHeight.replace("dp", ""));
        assertTrue(layout + " touch target", minHeightDp >= 48);
    }

    private Element findById(String layout, String id) throws Exception {
        File file = new File(resDir, layout);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("*");
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

    private Element find(String unused, String layout) throws Exception {
        return findById(layout, "googleSignIn");
    }

    private static String attr(Element node, String name) {
        String value = node.getAttributeNS(ANDROID, name);
        if (value == null || value.isEmpty()) {
            value = node.getAttribute("android:" + name);
        }
        return value;
    }
}
