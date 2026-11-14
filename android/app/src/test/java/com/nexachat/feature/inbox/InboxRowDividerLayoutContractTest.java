package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The row divider follows the tallest content through a barrier, so a large font scale never presses
 * the preview line against the divider (a fixed bottom padding was ignored by the parent-bottom anchor).
 */
public final class InboxRowDividerLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    @Test
    public void dividerHangsFromABarrierOverAvatarPreviewAndChips() throws Exception {
        NodeList nodes = document().getElementsByTagName("*");
        Element barrier = null;
        Element divider = null;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if (e.getTagName().endsWith("Barrier")) barrier = e;
            if ("1dp".equals(e.getAttributeNS(ANDROID, "layout_height"))
                    && "@id/title".equals(e.getAttributeNS(APP, "layout_constraintStart_toStartOf"))) divider = e;
        }
        assertTrue("barrier present", barrier != null);
        assertEquals("bottom", barrier.getAttributeNS(APP, "barrierDirection"));
        String ids = barrier.getAttributeNS(APP, "constraint_referenced_ids");
        for (String id : new String[]{"avatar", "preview", "labelChips"}) {
            assertTrue(id + " must be under the barrier", ids.contains(id));
        }
        assertTrue("divider present", divider != null);
        assertEquals("@id/rowContentBottom", divider.getAttributeNS(APP, "layout_constraintTop_toBottomOf"));
        assertEquals("@dimen/space_sm", divider.getAttributeNS(ANDROID, "layout_marginTop"));
    }

    private static org.w3c.dom.Document document() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        File f = new File("src/main/res/layout/item_inbox_conversation.xml");
        if (!f.isFile()) f = new File("app/src/main/res/layout/item_inbox_conversation.xml");
        return factory.newDocumentBuilder().parse(f);
    }
}