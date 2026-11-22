package com.nexachat.feature.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Wiring contract for the Ask sheet's scroll behaviour and a11y counter. */
public final class AskSheetContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void renderScrollsToBottomWhenTurnsGrow() throws Exception {
        String source = read(
                "src/main/java/com/nexachat/feature/ask/AskBottomSheetFragment.java");
        assertTrue("new turns must trigger a posted scroll to the bottom",
                source.contains("fullScroll(View.FOCUS_DOWN)"));
        int call = source.indexOf("fullScroll");
        int post = source.lastIndexOf(".post(", call);
        int arrow = source.lastIndexOf("->", call);
        assertTrue("the scroll must run inside a posted runnable",
                post >= 0 && arrow > post);
        String runnable = source.substring(arrow, source.indexOf(";", call));
        assertFalse("the posted runnable must not dereference binding;"
                        + " onDestroyView nulls it before the runnable may run",
                runnable.contains("binding."));
    }

    @Test
    public void askCounterIsAPoliteLiveRegion() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(Path.of("src/main/res/layout/bottom_sheet_ask_chat.xml").toFile());
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/askCounter").equals(node.getAttributeNS(ANDROID, "id"))) {
                assertEquals("polite",
                        node.getAttributeNS(ANDROID, "accessibilityLiveRegion"));
                return;
            }
        }
        throw new AssertionError("Missing askCounter in bottom_sheet_ask_chat.xml");
    }

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(Path.of(relative)), StandardCharsets.UTF_8);
    }
}
