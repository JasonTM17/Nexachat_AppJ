package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class InboxSearchCopyTest {

    @Test
    public void inboxSearchHintSaysFindPeopleInBothLocales() throws Exception {
        assertEquals("Find people", stringValue("values/strings.xml", "inbox_search_hint"));
        assertEquals("Tìm người", stringValue("values-vi/strings.xml", "inbox_search_hint"));
    }

    @Test
    public void inboxSearchUsesHintAsContentDescription() throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        File layout = locateLayout("fragment_inbox.xml");
        var doc = factory.newDocumentBuilder().parse(layout);
        String androidNs = "http://schemas.android.com/apk/res/android";
        var nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            var el = (org.w3c.dom.Element) nodes.item(i);
            if ("@+id/inboxSearchPill".equals(el.getAttributeNS(androidNs, "id"))) {
                assertEquals("@string/inbox_search_hint", el.getAttributeNS(androidNs, "contentDescription"));
                return;
            }
        }
        throw new AssertionError("inboxSearchPill missing in fragment_inbox.xml");
    }

    private static String stringValue(String relative, String name) throws Exception {
        File file = locateRes(relative);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        NodeList nodes = doc.getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (name.equals(el.getAttribute("name"))) {
                return el.getTextContent().trim();
            }
        }
        throw new AssertionError("Missing string " + name + " in " + relative);
    }

    private static File locateLayout(String name) {
        for (String base : new String[] {"src/main/res/layout/", "app/src/main/res/layout/", "android/app/src/main/res/layout/"}) {
            File file = new File(base + name);
            if (file.isFile()) {
                return file;
            }
        }
        throw new AssertionError("Missing layout " + name);
    }

    private static File locateRes(String relative) {
        for (String base : new String[] {"src/main/res/", "app/src/main/res/", "android/app/src/main/res/"}) {
            File file = new File(base + relative);
            if (file.isFile()) {
                return file;
            }
        }
        throw new AssertionError("Missing " + relative);
    }
}
