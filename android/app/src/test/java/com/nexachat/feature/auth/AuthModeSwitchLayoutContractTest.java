package com.nexachat.feature.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The sign-in/create-account mode switch must be a single-selection
 * {@code MaterialButtonToggleGroup} so screen readers announce which mode is checked.
 */
public final class AuthModeSwitchLayoutContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";
    private static final String TOGGLE_GROUP =
            "com.google.android.material.button.MaterialButtonToggleGroup";

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
    public void modeSwitchIsASingleSelectionToggleGroup() throws Exception {
        assertToggleGroup("layout/fragment_login.xml", "authModeLogin", "authModeRegister");
        assertToggleGroup("layout/fragment_register.xml", "authModeRegister", "authModeLogin");
    }

    private void assertToggleGroup(String layout, String checkedId, String uncheckedId)
            throws Exception {
        Element group = findById(layout, "authModeSwitch");
        assertEquals(layout, TOGGLE_GROUP, group.getTagName());
        assertEquals(layout, "true", appAttr(group, "singleSelection"));
        assertEquals(layout, "true", appAttr(group, "selectionRequired"));
        assertEquals(layout, "@id/" + checkedId, appAttr(group, "checkedButton"));

        Element checked = findById(layout, checkedId);
        Element unchecked = findById(layout, uncheckedId);
        assertEquals(layout,
                "com.google.android.material.button.MaterialButton", checked.getTagName());
        assertEquals(layout,
                "com.google.android.material.button.MaterialButton", unchecked.getTagName());
    }

    @Test
    public void modeSwitchHasNoDeadSecondaryAction() throws Exception {
        assertNull("fragment_login.xml still carries secondaryAction",
                findByIdOrNull("layout/fragment_login.xml", "secondaryAction"));
        assertNull("fragment_register.xml still carries secondaryAction",
                findByIdOrNull("layout/fragment_register.xml", "secondaryAction"));
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

    private static String appAttr(Element node, String name) {
        String value = node.getAttributeNS(APP, name);
        if (value == null || value.isEmpty()) {
            value = node.getAttribute("app:" + name);
        }
        return value;
    }
}
