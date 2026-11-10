package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.fail;

/**
 * JVM layout audit for accessibility invariants under {@code res/layout*}:
 * actionable icons have content descriptions and meet the 48dp touch target.
 */
public final class LayoutAccessibilityUnitTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";
    private static final Pattern DP_PATTERN = Pattern.compile("(\\d+)dp");

    private File resDir;

    @Before
    public void setUp() {
        resDir = locateResDir();
    }

    private static File locateResDir() {
        File[] candidates = new File[] {
                new File("src/main/res"),
                new File("app/src/main/res"),
                new File("android/app/src/main/res"),
                new File("../app/src/main/res")
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory() && new File(candidate, "values/colors_nexachat.xml").isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate res directory");
    }

    private static final Pattern HEX_COLOR_IN_LAYOUT =
            Pattern.compile("android:(textColor|background|fillColor|tint)=\"#");

    @Test
    public void layoutsAvoidHardcodedHexColors() throws Exception {
        List<String> violations = new ArrayList<>();
        scanLayoutFilesForHex(new File(resDir, "layout"), violations);
        File layoutLand = new File(resDir, "layout-land");
        if (layoutLand.isDirectory()) {
            scanLayoutFilesForHex(layoutLand, violations);
        }
        if (!violations.isEmpty()) {
            fail("Hardcoded hex colors in layouts:\n" + String.join("\n", violations));
        }
    }

    private static void scanLayoutFilesForHex(File dir, List<String> violations) {
        if (!dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(
                    file.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line).append('\n');
                }
                if (HEX_COLOR_IN_LAYOUT.matcher(body).find()) {
                    violations.add(file.getName());
                }
            } catch (java.io.IOException ignored) {
                violations.add(file.getName() + " (unreadable)");
            }
        }
    }

    @Test
    public void imageButtonsHaveContentDescriptionAndMinTouchTarget() throws Exception {
        List<String> violations = new ArrayList<>();
        File layoutRoot = new File(resDir, "layout");
        scanLayoutDir(layoutRoot, violations);
        File layoutLand = new File(resDir, "layout-land");
        if (layoutLand.isDirectory()) {
            scanLayoutDir(layoutLand, violations);
        }
        if (!violations.isEmpty()) {
            fail("Layout accessibility violations:\n" + String.join("\n", violations));
        }
    }

  private void scanLayoutDir(File dir, List<String> violations) throws Exception {
        if (!dir.isDirectory()) {
            return;
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            var doc = builder.parse(file);
            walk(doc.getDocumentElement(), file, violations);
        }
    }

    private void walk(Node node, File file, List<String> violations) {
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            return;
        }
        Element el = (Element) node;
        String tag = stripNs(el.getTagName());
        if ("ImageButton".equals(tag)) {
            String imp = attr(el, "importantForAccessibility");
            if (!"no".equals(imp)) {
                String cd = attr(el, "contentDescription");
                if (cd.isEmpty()) {
                    violations.add(file.getName() + ": ImageButton " + el.getAttributeNS(NS_ANDROID, "id")
                            + " missing contentDescription");
                }
                checkMinTouch(el, file, violations);
            }
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            walk(children.item(i), file, violations);
        }
    }

    private static void checkMinTouch(Element el, File file, List<String> violations) {
        String width = attr(el, "layout_width");
        String height = attr(el, "layout_height");
        if (width.contains("@dimen/min_touch_target") && height.contains("@dimen/min_touch_target")) {
            return;
        }
        Integer wDp = parseDp(width);
        Integer hDp = parseDp(height);
        if (wDp != null && wDp < 48) {
            violations.add(file.getName() + ": ImageButton touch width " + width + " < 48dp");
        }
        if (hDp != null && hDp < 48) {
            violations.add(file.getName() + ": ImageButton touch height " + height + " < 48dp");
        }
    }

    private static Integer parseDp(String dimen) {
        if (dimen == null) {
            return null;
        }
        Matcher m = DP_PATTERN.matcher(dimen);
        if (m.matches()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }

    private static String stripNs(String tag) {
        int idx = tag.indexOf(':');
        return idx >= 0 ? tag.substring(idx + 1) : tag;
    }

    private static String attr(Element el, String localName) {
        String value = el.getAttributeNS(NS_ANDROID, localName);
        if (value == null || value.isEmpty()) {
            value = el.getAttribute("android:" + localName);
        }
        return value == null ? "" : value;
    }
}
