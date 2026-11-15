package com.nexachat.feature.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Stitch conformance contract for the contacts list and user-search rows: faint tonal
 * dividers, Inter title names, the borderless grouped band behind each list, and the
 * FilledBox theme search field (no outlined override).
 */
public final class ContactsListLayoutContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";
    private static final String TITLE_STYLE = "@style/TextAppearance.NexaChat.TitleMedium";
    private static final String TONAL_DIVIDER = "?attr/colorSurfaceContainerHighest";
    private static final String BAND_DRAWABLE = "@drawable/bg_grouped_list_band";

    @Test
    public void contactRows_useTonalDividerAndInterTitle() throws Exception {
        Document doc = parse("layout/item_contact.xml");
        assertEquals(TITLE_STYLE, attr(findById(doc, "name"), "textAppearance"));
        assertEquals(TONAL_DIVIDER, attr(findRowDivider(doc), "background"));
    }

    @Test
    public void userSearchRows_useTonalDividerAndInterTitle() throws Exception {
        Document doc = parse("layout/item_user_search.xml");
        assertEquals(TITLE_STYLE, attr(findById(doc, "name"), "textAppearance"));
        assertEquals(TONAL_DIVIDER, attr(findRowDivider(doc), "background"));
    }

    @Test
    public void contactList_sitsInTonalGroupedBand() throws Exception {
        Element list = findById(parse("layout/fragment_contacts.xml"), "contactList");
        assertEquals(BAND_DRAWABLE, attr(list, "background"));
        assertEquals("true", attr(list, "clipToOutline"));
        assertEquals("band must hug the rows, not fill the viewport",
                "wrap_content", attr(list, "layout_height"));
    }

    @Test
    public void userSearchResults_sitInTonalGroupedBand() throws Exception {
        Element list = findById(parse("layout/fragment_user_search.xml"), "resultsList");
        assertEquals(BAND_DRAWABLE, attr(list, "background"));
        assertEquals("true", attr(list, "clipToOutline"));
        assertEquals("band must hug the rows, not fill the viewport",
                "wrap_content", attr(list, "layout_height"));
    }

    @Test
    public void userSearchField_usesFilledBoxTheme() throws Exception {
        Element field = findById(parse("layout/fragment_user_search.xml"), "searchLayout");
        assertEquals("@style/Widget.NexaChat.TextInputLayout", field.getAttribute("style"));
        assertEquals("", field.getAttributeNS(APP, "boxStrokeWidth"));
    }

    @Test
    public void groupedBand_isTonalLowestWithCardRadiusAndNoStroke() throws Exception {
        Document doc = parse("drawable/bg_grouped_list_band.xml");
        Element solid = firstByTag(doc, "solid");
        Element corners = firstByTag(doc, "corners");
        assertNotNull("band drawable must fill with a tonal surface", solid);
        assertEquals("@color/colorSurfaceContainerLowest",
                solid.getAttributeNS(ANDROID, "color"));
        assertNotNull("band drawable must round its corners", corners);
        assertEquals("@dimen/corner_md", corners.getAttributeNS(ANDROID, "radius"));
        assertEquals("no stroke element", 0, doc.getElementsByTagName("stroke").getLength());
    }

    private static Element findById(Document doc, String id) {
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            String value = e.getAttributeNS(ANDROID, "id");
            if (value.endsWith("/" + id)) {
                return e;
            }
        }
        throw new AssertionError("no element with id @" + id);
    }

    private static Element findRowDivider(Document doc) {
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if ("View".equals(e.getTagName())
                    && "1dp".equals(e.getAttributeNS(ANDROID, "layout_height"))) {
                return e;
            }
        }
        throw new AssertionError("no 1dp divider View found");
    }

    private static Element firstByTag(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String attr(Element e, String localName) {
        return e.getAttributeNS(ANDROID, localName);
    }

    private static Document parse(String relative) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        File[] candidates = {
                new File("src/main/res/" + relative),
                new File("app/src/main/res/" + relative),
                new File("android/app/src/main/res/" + relative)
        };
        for (File f : candidates) {
            if (f.isFile()) {
                return factory.newDocumentBuilder().parse(f);
            }
        }
        throw new AssertionError("Could not locate res/" + relative);
    }
}
