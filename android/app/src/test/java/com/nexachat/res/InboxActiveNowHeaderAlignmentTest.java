package com.nexachat.res;

import static org.junit.Assert.assertEquals;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** The Active Now header aligns with the 16dp content margin used by the rail and the list. */
public final class InboxActiveNowHeaderAlignmentTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void viewAllSitsOnTheSameRightMarginAsTheContent() throws Exception {
        File root = new File("src/main/res/layout");
        if (!root.isDirectory()) root = new File("app/src/main/res/layout");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder()
                .parse(new File(root, "fragment_inbox.xml")).getElementsByTagName("*");
        Element viewAll = null;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if ("@+id/activeNowViewAll".equals(e.getAttributeNS(ANDROID, "id"))) viewAll = e;
        }
        Element header = (Element) viewAll.getParentNode();
        assertEquals("16dp", header.getAttributeNS(ANDROID, "paddingStart"));
        assertEquals("16dp", header.getAttributeNS(ANDROID, "paddingEnd"));
    }
}