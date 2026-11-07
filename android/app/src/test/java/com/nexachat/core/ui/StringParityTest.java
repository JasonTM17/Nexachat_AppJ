package com.nexachat.core.ui;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Regression test enforcing 100% string key parity between
 * res/values/strings.xml (English) and res/values-vi/strings.xml (Vietnamese).
 */
public class StringParityTest {

    @Test
    public void verifyEnglishAndVietnameseStringsHaveExactParity() {
        File resDir = findResDir();

        File enFile = new File(resDir, "values/strings.xml");
        File viFile = new File(resDir, "values-vi/strings.xml");

        Assert.assertTrue("English strings.xml should exist", enFile.exists());
        Assert.assertTrue("Vietnamese strings.xml should exist", viFile.exists());

        Set<String> enKeys = extractStringKeys(enFile);
        Set<String> viKeys = extractStringKeys(viFile);

        Set<String> missingInVi = new HashSet<>(enKeys);
        missingInVi.removeAll(viKeys);
        Set<String> extraInVi = new HashSet<>(viKeys);
        extraInVi.removeAll(enKeys);

        Assert.assertTrue("Keys present in EN but missing in VI: " + missingInVi, missingInVi.isEmpty());
        Assert.assertTrue("Keys present in VI but missing in EN: " + extraInVi, extraInVi.isEmpty());
        Assert.assertEquals("String count should match exactly", enKeys.size(), viKeys.size());
    }

    @Test
    public void verifyCallStringsHaveExactParity() {
        File resDir = findResDir();

        File enFile = new File(resDir, "values/strings_call.xml");
        File viFile = new File(resDir, "values-vi/strings_call.xml");
        Assert.assertTrue("English call strings should exist", enFile.exists());
        Assert.assertTrue("Vietnamese call strings should exist", viFile.exists());

        Set<String> enKeys = extractStringKeys(enFile);
        Set<String> viKeys = extractStringKeys(viFile);
        Assert.assertTrue("Call keys present in EN but missing in VI: " + difference(enKeys, viKeys),
                enKeys.equals(viKeys));
    }

    @Test
    public void verifySummaryStringsHaveExactParity() {
        File resDir = findResDir();

        File enFile = new File(resDir, "values/strings_summary.xml");
        File viFile = new File(resDir, "values-vi/strings_summary.xml");
        Assert.assertTrue("English summary strings should exist", enFile.exists());
        Assert.assertTrue("Vietnamese summary strings should exist", viFile.exists());

        Set<String> enKeys = extractStringKeys(enFile);
        Set<String> viKeys = extractStringKeys(viFile);

        Set<String> missingInVi = new HashSet<>(enKeys);
        missingInVi.removeAll(viKeys);
        Set<String> extraInVi = new HashSet<>(viKeys);
        extraInVi.removeAll(enKeys);

        Assert.assertTrue("Summary keys present in EN but missing in VI: " + missingInVi, missingInVi.isEmpty());
        Assert.assertTrue("Summary keys present in VI but missing in EN: " + extraInVi, extraInVi.isEmpty());
        Assert.assertEquals("Summary string count should match exactly", enKeys.size(), viKeys.size());
    }

    private static File findResDir() {
        File rootDir = new File(".").getAbsoluteFile();
        File[] candidates = {
                new File("src/main/res"),
                new File("app/src/main/res"),
                new File("android/app/src/main/res"),
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot find res directory from " + rootDir.getPath());
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static Set<String> extractStringKeys(File file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(file);
            NodeList nodes = doc.getElementsByTagName("string");
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                String name = element.getAttribute("name");
                if (!name.trim().isEmpty()) {
                    keys.add(name);
                }
            }
            return keys;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse string resources: " + file, e);
        }
    }
}
