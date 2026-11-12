package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for count-with-noun plural resources used by {@link QuantityStrings}.
 */
public final class QuantityStringsPluralsTest {

    private static final Set<String> REQUIRED_PLURALS = Set.of(
            "invite_member_count",
            "invite_usage_unlimited",
            "invite_usage_limited",
            "poll_voter_count",
            "poll_option_count",
            "unread_banner_format",
            "ask_rate_limited_retry",
            "inbox_unread_messages"
    );

    private File resDir;

    @Before
    public void setUp() {
        resDir = locateResDir();
    }

    private static File locateResDir() {
        File[] candidates = new File[]{
                new File("src/main/res"),
                new File("app/src/main/res"),
                new File("android/app/src/main/res"),
                new File("../app/src/main/res")
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory() && new File(candidate, "values/strings.xml").isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate Android res dir");
    }

    private Map<String, Map<String, String>> loadPlurals(String valuesFolder) throws Exception {
        Map<String, Map<String, String>> merged = new HashMap<>();
        File folder = new File(resDir, valuesFolder);
        File[] files = folder.listFiles((dir, name) -> name.startsWith("strings") && name.endsWith(".xml"));
        if (files == null) {
            return merged;
        }
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        for (File file : files) {
            Document doc = builder.parse(file);
            NodeList pluralNodes = doc.getDocumentElement().getElementsByTagName("plurals");
            for (int i = 0; i < pluralNodes.getLength(); i++) {
                Element plural = (Element) pluralNodes.item(i);
                String name = plural.getAttribute("name");
                Map<String, String> items = new HashMap<>();
                NodeList itemNodes = plural.getElementsByTagName("item");
                for (int j = 0; j < itemNodes.getLength(); j++) {
                    Element item = (Element) itemNodes.item(j);
                    items.put(item.getAttribute("quantity"), item.getTextContent());
                }
                merged.put(name, items);
            }
        }
        return merged;
    }

    @Test
    public void requiredPluralsPresentInBothLocales() throws Exception {
        Map<String, Map<String, String>> en = loadPlurals("values");
        Map<String, Map<String, String>> vi = loadPlurals("values-vi");
        for (String key : REQUIRED_PLURALS) {
            assertTrue("EN missing plural " + key, en.containsKey(key));
            assertTrue("VI missing plural " + key, vi.containsKey(key));
            assertTrue(key + " missing other in EN", en.get(key).containsKey("other"));
            assertTrue(key + " missing other in VI", vi.get(key).containsKey("other"));
            assertEquals(key + " VI must only use other", Set.of("other"), vi.get(key).keySet());
        }
    }

    @Test
    public void englishSingularFormsDifferForUnitCounts() throws Exception {
        Map<String, Map<String, String>> en = loadPlurals("values");
        for (String key : REQUIRED_PLURALS) {
            if ("unread_banner_format".equals(key)) {
                continue;
            }
            Map<String, String> items = en.get(key);
            assertTrue(key + " missing one", items.containsKey("one"));
            assertFalse(
                    key + " one/other should differ in EN",
                    items.get("one").equals(items.get("other")));
        }
    }

    @Test
    public void formattedEnglishCopyUsesSingularNouns() throws Exception {
        Map<String, Map<String, String>> en = loadPlurals("values");
        assertFormattedCount(en, "poll_voter_count", 1, "1 voter");
        assertFormattedCount(en, "poll_voter_count", 4, "4 voters");
        assertFormattedCount(en, "poll_option_count", 1, "1 vote");
        assertFormattedCount(en, "invite_member_count", 1, "1 member");
        assertFormattedLimited(en, "invite_usage_limited", 2, 5, "Used 2 of 5 times");
        assertFormattedLimited(en, "invite_usage_limited", 1, 1, "Used 1 of 1 time");
        assertFormattedCount(en, "ask_rate_limited_retry", 1, "Try again in 1 second.");
        assertFormattedCount(en, "ask_rate_limited_retry", 10, "Try again in 10 seconds.");
    }

    @Test
    public void legacyCountStringsRemoved() throws Exception {
        Set<String> forbidden = new HashSet<>();
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        for (String valuesFolder : new String[]{"values", "values-vi"}) {
            File folder = new File(resDir, valuesFolder);
            File[] files = folder.listFiles((dir, name) -> name.startsWith("strings") && name.endsWith(".xml"));
            if (files == null) {
                continue;
            }
            for (File file : files) {
                Document doc = builder.parse(file);
                NodeList strings = doc.getDocumentElement().getElementsByTagName("string");
                for (int i = 0; i < strings.getLength(); i++) {
                    forbidden.add(((Element) strings.item(i)).getAttribute("name"));
                }
            }
        }
        for (String pluralName : REQUIRED_PLURALS) {
            assertFalse("Still a <string> named " + pluralName, forbidden.contains(pluralName));
        }
    }

    @Test
    public void pluralsAreDirectChildrenAndDoNotNest() throws Exception {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        for (String valuesFolder : new String[]{"values", "values-vi"}) {
            File folder = new File(resDir, valuesFolder);
            File[] files = folder.listFiles((dir, name) -> name.startsWith("strings") && name.endsWith(".xml"));
            assertNotNull(valuesFolder, files);
            for (File file : files) {
                Document doc = builder.parse(file);
                assertTrue(file.getName() + " parses as resources",
                        "resources".equals(doc.getDocumentElement().getNodeName()));
                NodeList pluralNodes = doc.getElementsByTagName("plurals");
                for (int i = 0; i < pluralNodes.getLength(); i++) {
                    Element plural = (Element) pluralNodes.item(i);
                    assertEquals(file.getName() + " plurals must be a direct child of resources",
                            "resources", plural.getParentNode().getNodeName());
                    assertEquals(file.getName() + " plurals must not nest another plurals",
                            0, plural.getElementsByTagName("plurals").getLength());
                }
            }
        }
    }

    @Test
    public void nestedPluralsFixtureFailsTheDirectChildRule() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <plurals name="chat_unread_divider">
                        <item quantity="other">%d unread messages</item>
                        <plurals name="inbox_unread_messages">
                            <item quantity="other">%d unread messages</item>
                        </plurals>
                    </plurals>
                </resources>
                """;
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));
        assertFalse(allPluralsAreDirectUnnestedChildren(doc));
    }

    @Test
    public void vietnameseInChatSearchCopyStaysContextual() throws Exception {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = builder.parse(new File(resDir, "values-vi/strings.xml"));
        Map<String, String> strings = new HashMap<>();
        NodeList nodes = doc.getDocumentElement().getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            strings.put(element.getAttribute("name"), element.getTextContent());
        }
        assertEquals("Không tìm kiếm được. Hãy thử lại.", strings.get("search_in_chat_error"));
        assertEquals("Xóa tìm kiếm", strings.get("cd_clear_search"));
        assertEquals("Tìm người", strings.get("search_title"));
        assertEquals("Tìm trong trò chuyện", strings.get("cd_search_in_chat"));
    }

    private static boolean allPluralsAreDirectUnnestedChildren(Document doc) {
        NodeList pluralNodes = doc.getElementsByTagName("plurals");
        if (pluralNodes.getLength() == 0) {
            return false;
        }
        for (int i = 0; i < pluralNodes.getLength(); i++) {
            Node plural = pluralNodes.item(i);
            if (!"resources".equals(plural.getParentNode().getNodeName())) {
                return false;
            }
            if (((Element) plural).getElementsByTagName("plurals").getLength() != 0) {
                return false;
            }
        }
        return true;
    }

    private static void assertFormattedCount(
            Map<String, Map<String, String>> localePlurals,
            String key,
            int count,
            String expected) {
        String pattern = localePlurals.get(key).get(count == 1 ? "one" : "other");
        String actual = String.format(Locale.US, pattern, count);
        assertEquals(expected, actual);
    }

    private static void assertFormattedLimited(
            Map<String, Map<String, String>> localePlurals,
            String key,
            int useCount,
            int maxUses,
            String expected) {
        String pattern = localePlurals.get(key).get(maxUses == 1 ? "one" : "other");
        String actual = String.format(Locale.US, pattern, useCount, maxUses);
        assertEquals(expected, actual);
    }
}
