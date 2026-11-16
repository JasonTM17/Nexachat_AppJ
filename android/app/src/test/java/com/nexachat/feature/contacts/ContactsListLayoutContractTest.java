package com.nexachat.feature.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Stitch conformance contract for the contacts screen and user-search composition:
 * the search pill on both screens, tonal entry cards for the destinations that
 * exist, per-section grouped bands with faint dividers, Inter title names, the
 * letter-badge section header, and the pill search field (no outlined override).
 */
public final class ContactsListLayoutContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";
    private static final String TITLE_STYLE = "@style/TextAppearance.NexaChat.TitleMedium";
    private static final String TONAL_DIVIDER = "?attr/colorSurfaceContainerHighest";
    private static final String BAND_DRAWABLE = "@drawable/bg_grouped_list_band";
    private static final String PILL_DRAWABLE = "@drawable/bg_search_pill";

    @Test
    public void contactRows_useTonalDividerAndInterTitle() throws Exception {
        Document doc = parse("layout/item_contact.xml");
        assertEquals(TITLE_STYLE, attr(findById(doc, "name"), "textAppearance"));
        assertEquals(TONAL_DIVIDER, attr(findById(doc, "rowDivider"), "background"));
    }

    @Test
    public void userSearchRows_useTonalDividerAndInterTitle() throws Exception {
        Document doc = parse("layout/item_user_search.xml");
        assertEquals(TITLE_STYLE, attr(findById(doc, "name"), "textAppearance"));
        assertEquals(TONAL_DIVIDER, attr(findById(doc, "rowDivider"), "background"));
    }

    @Test
    public void contactRow_carriesItsOwnBandAndRipple() throws Exception {
        Element root = parse("layout/item_contact.xml").getDocumentElement();
        assertEquals(BAND_DRAWABLE, attr(root, "background"));
        assertEquals("?attr/selectableItemBackground", attr(root, "foreground"));
        assertNotNull("row divider must be addressable so the last row can hide it",
                findById(root.getOwnerDocument(), "rowDivider"));
    }

    @Test
    public void contactsScreen_hasOnScreenSearchPill() throws Exception {
        Element pill = findById(parse("layout/fragment_contacts.xml"), "searchPill");
        assertEquals(PILL_DRAWABLE, attr(pill, "background"));
        assertEquals("true", attr(pill, "clickable"));
        assertEquals("true", attr(pill, "focusable"));
        assertEquals("@dimen/min_touch_target", attr(pill, "minHeight"));
        assertEquals("@string/inbox_search_hint", attr(pill, "contentDescription"));
        assertEquals("?attr/selectableItemBackground", attr(pill, "foreground"));
        assertEquals("true", attr(pill, "clipToOutline"));
        // The toolbar icon keeps its id/label and the pill reuses the same destination.
        Element icon = findById(pill.getOwnerDocument(), "searchButton");
        assertEquals("@string/search_title", attr(icon, "contentDescription"));
    }

    @Test
    public void contactsScreen_hasEntryCardsForExistingDestinations() throws Exception {
        Document doc = parse("layout/fragment_contacts.xml");
        for (String id : new String[] {"addContactEntry", "createGroupEntry"}) {
            Element card = findById(doc, id);
            assertEquals("entry cards must be the shared settings row",
                    "com.nexachat.core.ui.components.SettingsRowView", card.getTagName());
            assertEquals(BAND_DRAWABLE, attr(card, "background"));
            assertEquals("true", attr(card, "clipToOutline"));
        }
    }

    @Test
    public void contactList_carriesNoViewportBand() throws Exception {
        Element list = findById(parse("layout/fragment_contacts.xml"), "contactList");
        assertEquals("bands now wrap each letter section on the rows, not the viewport",
                "", attr(list, "background"));
        assertEquals("the list fills the scrolling region so the header can collapse",
                "match_parent", attr(list, "layout_height"));
        assertEquals("no add-contact FAB: the entry card is the add path",
                0, countById(list.getOwnerDocument(), "addContactFab"));
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
    public void userSearchField_isPillNotOutlinedBox() throws Exception {
        Document doc = parse("layout/fragment_user_search.xml");
        Element pill = findById(doc, "searchBar");
        assertEquals(PILL_DRAWABLE, attr(pill, "background"));
        assertEquals("@dimen/min_touch_target", attr(pill, "minHeight"));
        findById(doc, "searchInput");
        assertEquals("no outlined TextInputLayout remains", 0,
                doc.getElementsByTagName(
                        "com.google.android.material.textfield.TextInputLayout").getLength());
    }

    @Test
    public void userSearch_leadingIconSubmitsSearch() throws Exception {
        Element icon = findById(parse("layout/fragment_user_search.xml"), "searchSubmitButton");
        assertEquals("the pill leading icon is a touch-sized button, not decoration",
                "ImageButton", icon.getTagName());
        assertEquals("@string/search_title", attr(icon, "contentDescription"));
        assertEquals("@dimen/min_touch_target", attr(icon, "layout_width"));
        assertEquals("@dimen/min_touch_target", attr(icon, "layout_height"));
        assertTrue("the leading icon must submit the same search as the IME action",
                source("java/com/nexachat/feature/contacts/UserSearchFragment.java")
                        .contains("searchSubmitButton.setOnClickListener(v -> viewModel.search())"));
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

    @Test
    public void sectionBandVariants_roundTheRightCorners() throws Exception {
        Document top = parse("drawable/bg_grouped_list_band_top.xml");
        Element topCorners = firstByTag(top, "corners");
        assertEquals("@dimen/corner_md", topCorners.getAttributeNS(ANDROID, "topLeftRadius"));
        assertEquals("@dimen/corner_md", topCorners.getAttributeNS(ANDROID, "topRightRadius"));
        assertEquals("", topCorners.getAttributeNS(ANDROID, "bottomLeftRadius"));
        Document bottom = parse("drawable/bg_grouped_list_band_bottom.xml");
        Element bottomCorners = firstByTag(bottom, "corners");
        assertEquals("@dimen/corner_md", bottomCorners.getAttributeNS(ANDROID, "bottomLeftRadius"));
        assertEquals("@dimen/corner_md", bottomCorners.getAttributeNS(ANDROID, "bottomRightRadius"));
        assertEquals("", bottomCorners.getAttributeNS(ANDROID, "topLeftRadius"));
        Document mid = parse("drawable/bg_grouped_list_band_mid.xml");
        assertEquals("middle rows are square", 0, mid.getElementsByTagName("corners").getLength());
        for (String name : new String[] {
                "bg_grouped_list_band_top", "bg_grouped_list_band_bottom", "bg_grouped_list_band_mid"}) {
            Element solid = firstByTag(parse("drawable/" + name + ".xml"), "solid");
            assertEquals("@color/colorSurfaceContainerLowest", solid.getAttributeNS(ANDROID, "color"));
        }
    }

    @Test
    public void sectionHeader_isLetterBadgeOnCanvas() throws Exception {
        Document doc = parse("layout/item_contact_section.xml");
        Element root = doc.getDocumentElement();
        assertEquals("?android:attr/colorBackground", attr(root, "background"));
        Element letter = findById(doc, "sectionLetter");
        assertEquals("?attr/textAppearanceLabelMedium", attr(letter, "textAppearance"));
        assertEquals("?attr/colorPrimary", attr(letter, "textColor"));
        Element badge = parse("drawable/bg_letter_badge.xml").getDocumentElement();
        assertEquals("oval", badge.getAttributeNS(ANDROID, "shape"));
        assertEquals("@color/colorSurfaceContainer",
                firstByTag(badge.getOwnerDocument(), "solid").getAttributeNS(ANDROID, "color"));
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

    private static int countById(Document doc, String id) {
        NodeList nodes = doc.getElementsByTagName("*");
        int count = 0;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if (e.getAttributeNS(ANDROID, "id").endsWith("/" + id)) {
                count++;
            }
        }
        return count;
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

    private static String source(String relative) throws Exception {
        File[] candidates = {
                new File("src/main/" + relative),
                new File("app/src/main/" + relative),
                new File("android/app/src/main/" + relative)
        };
        for (File f : candidates) {
            if (f.isFile()) {
                return new String(Files.readAllBytes(f.toPath()));
            }
        }
        throw new AssertionError("Could not locate src/main/" + relative);
    }
}
