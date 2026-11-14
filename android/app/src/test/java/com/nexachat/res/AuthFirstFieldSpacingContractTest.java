package com.nexachat.res;

import static org.junit.Assert.assertEquals;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The first auth field keeps its own top gap, so the mode tabs never touch it when the Google
 * button and the "or" divider are hidden. The divider adds no bottom gap, so with Google shown the
 * spacing stays unchanged.
 */
public final class AuthFirstFieldSpacingContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void loginPhoneFieldOwnsItsTopGap() throws Exception {
        assertEquals("@dimen/space_md", margin("fragment_login.xml", "phoneLayout", "layout_marginTop"));
    }

    @Test
    public void registerNameFieldOwnsItsTopGap() throws Exception {
        assertEquals("@dimen/space_md", margin("fragment_register.xml", "nameLayout", "layout_marginTop"));
    }

    @Test
    public void orDividerAddsNoBottomGap() throws Exception {
        File root = layoutRoot();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Element divider = factory.newDocumentBuilder()
                .parse(new File(root, "include_auth_or_divider.xml")).getDocumentElement();
        assertEquals("", divider.getAttributeNS(ANDROID, "layout_marginBottom"));
    }

    private static String margin(String layout, String id, String attr) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder()
                .parse(new File(layoutRoot(), layout)).getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if (("@+id/" + id).equals(e.getAttributeNS(ANDROID, "id"))) {
                return e.getAttributeNS(ANDROID, attr);
            }
        }
        throw new AssertionError("Missing " + id);
    }

    private static File layoutRoot() {
        File root = new File("src/main/res/layout");
        return root.isDirectory() ? root : new File("app/src/main/res/layout");
    }
}