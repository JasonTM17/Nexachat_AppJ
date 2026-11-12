package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class InboxUnreadBadgePluralTest {

    @Test
    public void conversationAdapterUsesUnreadPluralForContentDescription() throws Exception {
        Path src = locateAdapterSource();
        String body = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        assertTrue(body.contains("R.plurals.inbox_unread_messages"));
        assertTrue(body.contains("getQuantityString"));
    }

    @Test
    public void unreadBadgePluralExistsInBothLocales() throws Exception {
        assertPluralQuantity("values/strings.xml", "inbox_unread_messages", "one", true);
        assertPluralQuantity("values-vi/strings.xml", "inbox_unread_messages", "other", true);
        assertPluralQuantity("values/strings.xml", "inbox_unread_messages", "other", true);
    }

    private static void assertPluralQuantity(
            String relative,
            String pluralName,
            String quantity,
            boolean expectPlaceholder) throws Exception {
        File file = locateRes(relative);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        NodeList pluralNodes = doc.getElementsByTagName("plurals");
        for (int i = 0; i < pluralNodes.getLength(); i++) {
            Element plural = (Element) pluralNodes.item(i);
            if (!pluralName.equals(plural.getAttribute("name"))) {
                continue;
            }
            NodeList items = plural.getElementsByTagName("item");
            for (int j = 0; j < items.getLength(); j++) {
                Element item = (Element) items.item(j);
                if (quantity.equals(item.getAttribute("quantity"))) {
                    String text = item.getTextContent();
                    if (expectPlaceholder) {
                        assertTrue(text, text.contains("%d"));
                    }
                    return;
                }
            }
            throw new AssertionError("Missing quantity " + quantity + " for " + pluralName);
        }
        throw new AssertionError("Missing plurals@" + pluralName + " in " + relative);
    }

    private static Path locateAdapterSource() {
        Path[] candidates = new Path[] {
                Path.of("src/main/java/com/nexachat/feature/inbox/adapter/ConversationListAdapter.java"),
                Path.of("app/src/main/java/com/nexachat/feature/inbox/adapter/ConversationListAdapter.java"),
                Path.of("android/app/src/main/java/com/nexachat/feature/inbox/adapter/ConversationListAdapter.java"),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Missing ConversationListAdapter.java");
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
