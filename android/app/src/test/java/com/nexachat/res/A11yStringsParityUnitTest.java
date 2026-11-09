package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/** Parity between {@code strings_a11y.xml} English and Vietnamese resource files. */
public final class A11yStringsParityUnitTest {

    private File resDir;

    @Before
    public void setUp() {
        File[] candidates = new File[] {
                new File("src/main/res"),
                new File("app/src/main/res"),
                new File("android/app/src/main/res"),
                new File("../app/src/main/res")
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory() && new File(candidate, "values/strings_a11y.xml").isFile()) {
                resDir = candidate;
                return;
            }
        }
        throw new IllegalStateException("Could not locate res directory");
    }

    @Test
    public void a11yStringKeysMatchBetweenLocales() throws Exception {
        Map<String, String> en = loadStrings(new File(resDir, "values/strings_a11y.xml"));
        Map<String, String> vi = loadStrings(new File(resDir, "values-vi/strings_a11y.xml"));
        Set<String> enKeys = en.keySet();
        Set<String> viKeys = vi.keySet();
        assertEquals("Vi a11y keys must match EN", enKeys, viKeys);
    }

    private static Map<String, String> loadStrings(File file) throws Exception {
        Map<String, String> map = new HashMap<>();
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = builder.parse(file);
        NodeList nodes = doc.getDocumentElement().getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            map.put(el.getAttribute("name"), el.getTextContent());
        }
        return map;
    }
}
