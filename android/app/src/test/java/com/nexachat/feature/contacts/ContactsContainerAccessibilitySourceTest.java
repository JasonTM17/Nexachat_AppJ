package com.nexachat.feature.contacts;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Pins the contacts-screen fixes for issue #254's second sweep: the
 * label-free contact list stops being a focus target while staying visible to
 * a11y services, and the search pill's decorative icon gains the layout id the
 * a11y oracle needs to resolve it as decoration (rid-based suppression only
 * matches id-carrying views). The contact rows themselves already compose
 * their descriptions (#212, {@code cd_contact_row}).
 */
public final class ContactsContainerAccessibilitySourceTest {

    @Test
    public void contactsFragment_suppressesTheLabelFreeList() throws Exception {
        String source = read("src/main/java/com/nexachat/feature/contacts/ContactsFragment.java");
        int at = source.indexOf("binding.contactList.setAdapter(adapter);");
        assertTrue(at >= 0);
        String window = source.substring(at, at + 500);
        assertTrue("contactList must be dropped from the focus-target set",
                window.contains("A11yContainerRules.suppressFocusTarget(\n                binding.contactList"));
    }

    @Test
    public void contactsLayout_marksSearchIconDecorativeWithAnId() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(locateLayout().toFile());
        NodeList views = doc.getElementsByTagName("*");
        Element found = null;
        for (int i = 0; i < views.getLength(); i++) {
            Element view = (Element) views.item(i);
            if (view.getAttribute("android:id").endsWith("/contactsSearchIcon")) {
                found = view;
                break;
            }
        }
        if (found == null) {
            throw new AssertionError("Missing view id contactsSearchIcon in fragment_contacts.xml");
        }
        assertTrue(found.getAttribute("android:importantForAccessibility") + " on contactsSearchIcon",
                "no".equals(found.getAttribute("android:importantForAccessibility")));
    }

    private static Path locateLayout() {
        for (String relative : new String[] {
                "src/main/res/layout/fragment_contacts.xml",
                "android/app/src/main/res/layout/fragment_contacts.xml",
                "app/src/main/res/layout/fragment_contacts.xml"}) {
            Path path = Path.of(relative);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new AssertionError("Could not locate fragment_contacts.xml");
    }

    private static String read(String relative) throws Exception {
        for (String prefix : new String[] {"", "android/app/", "app/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Could not locate " + relative);
    }
}
