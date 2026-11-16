package com.nexachat.feature.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The sign-in/create-account mode switch keeps a plain {@code LinearLayout} track
 * so the selected segment keeps all four square corners, while each
 * {@code MaterialButton} is checkable so screen readers announce the active mode.
 * Checked state is driven from the fragments ({@code toggleCheckedStateOnClick}
 * is off) so tapping the active mode can never uncheck it.
 */
public final class AuthModeSwitchLayoutContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";
    private static final String MODE_BUTTON =
            "com.google.android.material.button.MaterialButton";

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
    public void modeSwitchStaysAPlainContainerWithCheckableButtons() throws Exception {
        assertModeSwitch("layout/fragment_login.xml");
        assertModeSwitch("layout/fragment_register.xml");
    }

    private void assertModeSwitch(String layout) throws Exception {
        Element group = findById(layout, "authModeSwitch");
        assertEquals(layout, "LinearLayout", group.getTagName());
        for (String id : List.of("authModeLogin", "authModeRegister")) {
            Element button = findById(layout, id);
            assertEquals(layout + "#" + id, MODE_BUTTON, button.getTagName());
            assertEquals(layout + "#" + id, "true", androidAttr(button, "checkable"));
            assertEquals(layout + "#" + id,
                    "false", appAttr(button, "toggleCheckedStateOnClick"));
            assertEquals(layout + "#" + id + " must inherit the pill theme shape",
                    "", appAttr(button, "cornerRadius"));
        }
        assertEquals(layout + " switch track is a pill",
                "@drawable/bg_auth_mode_switch", androidAttr(group, "background"));
    }

    @Test
    public void activeModeIsMarkedCheckedByItsFragment() throws Exception {
        assertTrue("LoginFragment must mark authModeLogin checked",
                source("feature/auth/LoginFragment.java")
                        .contains("authModeLogin.setChecked(true)"));
        assertTrue("RegisterFragment must mark authModeRegister checked",
                source("feature/auth/RegisterFragment.java")
                        .contains("authModeRegister.setChecked(true)"));
    }

    @Test
    public void modeSwitchHasNoDeadSecondaryAction() throws Exception {
        assertNull("fragment_login.xml still carries secondaryAction",
                findByIdOrNull("layout/fragment_login.xml", "secondaryAction"));
        assertNull("fragment_register.xml still carries secondaryAction",
                findByIdOrNull("layout/fragment_register.xml", "secondaryAction"));
    }

    private static String source(String relative) throws Exception {
        List<File> candidates = List.of(
                new File("src/main/java/com/nexachat/" + relative),
                new File("app/src/main/java/com/nexachat/" + relative),
                new File("android/app/src/main/java/com/nexachat/" + relative),
                new File("../app/src/main/java/com/nexachat/" + relative));
        File file = candidates.stream()
                .filter(File::isFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Could not locate source " + relative));
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private Element findById(String layout, String id) throws Exception {
        Element found = findByIdOrNull(layout, id);
        if (found == null) {
            throw new AssertionError("Missing " + id + " in " + layout);
        }
        return found;
    }

    private Element findByIdOrNull(String layout, String id) throws Exception {
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
        return null;
    }

    private static String androidAttr(Element node, String name) {
        String value = node.getAttributeNS(ANDROID, name);
        if (value == null || value.isEmpty()) {
            value = node.getAttribute("android:" + name);
        }
        return value;
    }

    private static String appAttr(Element node, String name) {
        String value = node.getAttributeNS(APP, name);
        if (value == null || value.isEmpty()) {
            value = node.getAttribute("app:" + name);
        }
        return value;
    }
}
