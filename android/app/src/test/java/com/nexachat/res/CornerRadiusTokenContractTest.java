package com.nexachat.res;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Corner-radius token contract: no drawable or layout under {@code res/} may
 * hard-code a corner radius. Every radius attribute ({@code radius},
 * {@code cornerRadius}, {@code cardCornerRadius}, {@code chipCornerRadius},
 * {@code topLeftRadius}, ...) must be {@code 0dp} (square), a
 * {@code @dimen/corner_*} token, a theme attribute, or a documented exception
 * listed in {@link #EXCEPTIONS}. Oval and ring shapes (avatars, dots,
 * swatches) are fully round by definition and are skipped.
 */
public final class CornerRadiusTokenContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    /**
     * Documented exceptions, keyed by {@code dir/file} then attribute local
     * name. Keep empty unless a literal radius is a deliberate, reviewed
     * exception; prefer a {@code @dimen/corner_*} token instead.
     */
    private static final Set<String> EXCEPTIONS = Set.of();

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
            if (candidate.isDirectory() && new File(candidate, "values/colors_nexachat.xml").isFile()) {
                resDir = candidate;
                return;
            }
        }
        throw new IllegalStateException("Could not locate res directory");
    }

    @Test
    public void noDrawableOrLayoutHardCodesCornerRadius() throws Exception {
        List<String> failures = new ArrayList<>();
        for (File xml : resourceFiles()) {
            Document doc = parse(xml);
            collectViolations(doc.getDocumentElement(), xml, failures);
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    private void collectViolations(Element element, File file, List<String> failures) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String name = attribute.getLocalName() != null
                    ? attribute.getLocalName()
                    : attribute.getNodeName();
            if (!name.toLowerCase(Locale.ROOT).endsWith("adius")) {
                continue;
            }
            if (isInsideRoundShape(element) || allowedRadiusValue(attribute.getNodeValue())) {
                continue;
            }
            String key = relativePath(file) + "#" + name;
            if (!EXCEPTIONS.contains(key)) {
                failures.add(key + " = \"" + attribute.getNodeValue().trim()
                        + "\" (use 0dp or a @dimen/corner_* token)");
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                collectViolations(child, file, failures);
            }
        }
    }

    private boolean isInsideRoundShape(Element element) {
        Node node = element.getParentNode();
        while (node instanceof Element parent) {
            if ("shape".equals(parent.getTagName())) {
                String shape = parent.getAttributeNS(ANDROID, "shape");
                return "oval".equals(shape) || "ring".equals(shape);
            }
            node = parent.getParentNode();
        }
        return false;
    }

    private static boolean allowedRadiusValue(String raw) {
        String value = raw.trim();
        if (value.isEmpty() || value.equals("@null")) {
            return true;
        }
        if (value.matches("0(dp|px|sp)")) {
            return true;
        }
        if (value.startsWith("@dimen/corner_")) {
            return true;
        }
        return value.startsWith("?attr/");
    }

    private File[] resourceFiles() {
        List<File> files = new ArrayList<>();
        File[] dirs = resDir.listFiles();
        if (dirs == null) {
            throw new IllegalStateException("Could not list " + resDir);
        }
        for (File dir : dirs) {
            String name = dir.getName();
            boolean drawable = name.equals("drawable") || name.startsWith("drawable-");
            boolean layout = name.equals("layout") || name.startsWith("layout-");
            if (!dir.isDirectory() || (!drawable && !layout)) {
                continue;
            }
            collectXml(dir, files);
        }
        return files.toArray(new File[0]);
    }

    private void collectXml(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectXml(child, files);
            } else if (child.getName().endsWith(".xml")) {
                files.add(child);
            }
        }
    }

    private String relativePath(File file) {
        return file.getParentFile().getName() + "/" + file.getName();
    }

    private Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }
}
