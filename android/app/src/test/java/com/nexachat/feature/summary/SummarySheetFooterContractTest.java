package com.nexachat.feature.summary;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SummarySheetFooterContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void actionsStayOutsideTheScrollAndTheBodyIsConstrainedAboveThem() throws Exception {
        File root = new File("src/main/res/layout/bottom_sheet_summary.xml");
        if (!root.isFile()) root = new File("app/src/main/res/layout/bottom_sheet_summary.xml");
        if (!root.isFile()) root = new File("android/app/src/main/res/layout/bottom_sheet_summary.xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Element doc = factory.newDocumentBuilder().parse(root).getDocumentElement();
        Element actions = find(doc, "summaryActions");
        Element scroll = find(doc, "summarySheetScroll");
        assertFalse(isAncestor(scroll, actions));
        assertEquals("0dp", scroll.getAttributeNS(ANDROID, "layout_height"));
        assertEquals("1", scroll.getAttributeNS(ANDROID, "layout_weight"));
        assertTrue(actions.getParentNode() == scroll.getParentNode());
    }

    @Test
    public void footerStacksOnlyWhenDesiredSpanIncludingMarginsExceedsContentWidth() {
        int[] widths = {48, 132, 118};
        int[] margins = {0, 8, 8};
        int desired = SummaryBottomSheetFragment.SummaryFooterRules.desiredSpan(widths, margins);
        assertEquals(314, desired);
        assertFalse(SummaryBottomSheetFragment.SummaryFooterRules.stack(100, 200));
        assertTrue(SummaryBottomSheetFragment.SummaryFooterRules.stack(desired, 288));
        assertFalse(SummaryBottomSheetFragment.SummaryFooterRules.stack(desired, 0));
    }

    @Test
    public void stackedLayoutWidthMustNotMaskIntrinsicOverflow() {
        assertFalse(SummaryBottomSheetFragment.SummaryFooterRules.stack(48 + 90 + 150, 288));
        assertTrue(SummaryBottomSheetFragment.SummaryFooterRules.stack(314, 288));
    }

    @Test
    public void insetMathAddsTheLargerOfImeAndBarsOnce() {
        int base = 16;
        int applied = base + Math.max(48, 20);
        assertEquals(64, applied);
        assertEquals(base + 48, base + Math.max(48, 48));
    }

    private static Element find(Element root, String id) {
        NodeList nodes = root.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String value = element.getAttributeNS(ANDROID, "id");
            if (value.endsWith("/" + id)) return element;
        }
        throw new AssertionError("Missing " + id);
    }

    private static boolean isAncestor(Node ancestor, Node node) {
        Node parent = node.getParentNode();
        while (parent != null) {
            if (parent == ancestor) return true;
            parent = parent.getParentNode();
        }
        return false;
    }
}
