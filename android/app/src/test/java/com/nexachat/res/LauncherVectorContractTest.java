package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Geometry contract for the Note mark and its launcher layers. */
public final class LauncherVectorContractTest {

    static final String MARK_PATH = "M24 20H36L64 60V20H80V84H66L40 47V72L24 84Z";
    private static final double SAFE_RADIUS = 33.0;

    private File resDir;
    private File appDir;

    @Before
    public void setUp() {
        File module = new File("src/main/res");
        if (new File(module, "values/colors_nexachat.xml").isFile()) {
            resDir = module;
            appDir = new File(".");
            return;
        }
        File nested = new File("app/src/main/res");
        if (new File(nested, "values/colors_nexachat.xml").isFile()) {
            resDir = nested;
            appDir = new File("app");
            return;
        }
        throw new IllegalStateException("Could not locate Android resources");
    }

    @Test
    public void noteLayersKeepTheSourcePathAndSafeCircle() throws Exception {
        assertLayer(vector("drawable/ic_launcher_foreground.xml"), "@color/nexa_logo_on_brand", true);
        assertLayer(vector("drawable/ic_launcher_monochrome.xml"), "@android:color/black", true);
        assertLayer(vector("drawable/ic_logo_mark.xml"), "@color/nexa_logo_mark", false);
        assertLayer(vector("drawable/ic_notification.xml"), "@android:color/white", false);

        Document background = vector("drawable/ic_launcher_background.xml");
        assertEquals("108", viewport(background, "viewportWidth"));
        assertEquals("108", viewport(background, "viewportHeight"));
        assertNoForbiddenNodes(background);
        Element plate = onlyPath(background.getDocumentElement());
        assertEquals("@color/ic_launcher_background", plate.getAttribute("android:fillColor"));
        assertEquals("M0 0H108V108H0Z", plate.getAttribute("android:pathData"));
        String backgroundXml = read(new File(resDir, "drawable/ic_launcher_background.xml"));
        assertFalse(backgroundXml.contains("<group"));
    }

    @Test
    public void adaptiveIconsUseForegroundBackgroundAndMonochrome() throws Exception {
        for (String name : new String[] {"ic_launcher.xml", "ic_launcher_round.xml"}) {
            Document document = vector("mipmap-anydpi-v26/" + name);
            Element root = document.getDocumentElement();
            assertEquals("adaptive-icon", root.getTagName());
            assertEquals("@drawable/ic_launcher_background", childDrawable(root, "background"));
            assertEquals("@drawable/ic_launcher_foreground", childDrawable(root, "foreground"));
            assertEquals("@drawable/ic_launcher_monochrome", childDrawable(root, "monochrome"));
            assertNoForbiddenNodes(document);
        }
    }

    @Test
    public void markColorsFollowTheThemeAndThePlateDoesNot() throws Exception {
        Map<String, String> day = loadColors(false);
        Map<String, String> night = loadColors(true);
        assertEquals("#FF733F68", day.get("nexa_logo_mark"));
        assertEquals("#FFDDA7CC", night.get("nexa_logo_mark"));
        assertEquals("#FFFFFCFF", day.get("nexa_logo_on_brand"));
        assertEquals("#FFFFFCFF", night.get("nexa_logo_on_brand"));
        assertEquals("#FF733F68", day.get("ic_launcher_background"));
        assertEquals(day.get("ic_launcher_background"), night.get("ic_launcher_background"));
        assertTrue(ContrastUnitTest.computeContrastRatio(
                day.get("nexa_logo_on_brand"), day.get("ic_launcher_background")) >= 4.5);
        assertTrue(ContrastUnitTest.computeContrastRatio(
                day.get("nexa_logo_mark"), day.get("colorPrimaryContainer")) >= 4.5);
        assertTrue(ContrastUnitTest.computeContrastRatio(
                night.get("nexa_logo_mark"), night.get("colorPrimaryContainer")) >= 4.5);
    }

    @Test
    public void notificationSilhouetteIsWhiteAndAuthPlateIsNotTheMark() throws Exception {
        String notification = read(new File(resDir, "drawable/ic_notification.xml"));
        assertFalse(notification.contains("#"));
        assertFalse(notification.contains("@color/nexa_logo"));
        String plate = read(new File(resDir, "drawable/bg_auth_logo.xml"));
        assertTrue(plate.contains("?attr/colorPrimaryContainer"));
        assertFalse(plate.contains("?attr/colorPrimary\""));
        String source = read(new File(appDir, "src/main/java/com/nexachat/call/service/CallNotifications.java"));
        assertTrue(source.contains("R.drawable.ic_notification"));
        assertFalse(source.contains("setSmallIcon(android.R.drawable"));
    }

    @Test
    public void legacyLauncherPngsAreNotShippedAtMinSdk26() throws Exception {
        String gradle = read(new File(appDir, "build.gradle"));
        assertTrue(gradle.contains("minSdk 26"));
        for (String density : new String[] {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"}) {
            assertFalse(new File(resDir, "mipmap-" + density).exists());
        }
    }

    private void assertLayer(Document document, String fill, boolean safeCircle) {
        assertEquals("108", viewport(document, "viewportWidth"));
        assertEquals("108", viewport(document, "viewportHeight"));
        assertNoForbiddenNodes(document);
        Element path = onlyPath(document.getDocumentElement());
        assertEquals(fill, path.getAttribute("android:fillColor"));
        assertEquals(MARK_PATH, path.getAttribute("android:pathData"));
        Element group = groupOf(path);
        List<Point> points = parsePath(MARK_PATH);
        if (safeCircle) {
            assertNotNull(group);
            assertEquals("20.52", group.getAttribute("android:translateX"));
            assertEquals("20.52", group.getAttribute("android:translateY"));
            assertEquals("0.62", group.getAttribute("android:scaleX"));
            assertEquals("0.62", group.getAttribute("android:scaleY"));
            for (Point point : points) {
                double x = point.x * 0.62 + 20.52;
                double y = point.y * 0.62 + 20.52;
                double radius = Math.hypot(x - 54.0, y - 54.0);
                assertTrue("safe circle " + x + "," + y + " r=" + radius, radius <= SAFE_RADIUS);
            }
        } else {
            assertTrue(group == null);
        }
    }

    private static Element onlyPath(Element root) {
        List<Element> paths = new ArrayList<>();
        collect(root, "path", paths);
        assertEquals(1, paths.size());
        return paths.get(0);
    }

    private static Element groupOf(Element path) {
        Node parent = path.getParentNode();
        if (parent instanceof Element && "group".equals(((Element) parent).getTagName())) {
            return (Element) parent;
        }
        return null;
    }

    private static void collect(Element root, String tag, List<Element> into) {
        if (tag.equals(root.getTagName())) {
            into.add(root);
        }
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                collect((Element) child, tag, into);
            }
        }
    }

    private static void assertNoForbiddenNodes(Document document) {
        String[] forbidden = {"text", "clip-path", "bitmap", "gradient", "mask", "image"};
        for (String tag : forbidden) {
            assertEquals(tag, 0, document.getElementsByTagName(tag).getLength());
        }
    }

    private static String viewport(Document document, String attribute) {
        return document.getDocumentElement().getAttribute("android:" + attribute);
    }

    private static String childDrawable(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        assertEquals(tag, 1, nodes.getLength());
        return ((Element) nodes.item(0)).getAttribute("android:drawable");
    }

    private Document vector(String relative) throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File(resDir, relative));
    }

    private static String read(File file) throws Exception {
        assertTrue(file.getPath(), file.isFile());
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, String> loadColors(boolean night) throws Exception {
        Map<String, String> raw = new HashMap<>();
        loadColorDirectory(new File(resDir, "values"), raw);
        if (night) {
            loadColorDirectory(new File(resDir, "values-night"), raw);
        }
        Map<String, String> resolved = new HashMap<>();
        for (String name : raw.keySet()) {
            resolved.put(name, resolve(name, raw, new HashSet<>()));
        }
        return resolved;
    }

    private static void loadColorDirectory(File directory, Map<String, String> raw) throws Exception {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".xml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            Document document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(file);
            NodeList colors = document.getElementsByTagName("color");
            for (int i = 0; i < colors.getLength(); i++) {
                Element color = (Element) colors.item(i);
                if (color.hasAttribute("name")) {
                    raw.put(color.getAttribute("name"), color.getTextContent().trim());
                }
            }
        }
    }

    private static String resolve(String name, Map<String, String> raw, Set<String> stack) {
        if (!stack.add(name)) {
            throw new IllegalStateException("cycle at " + name);
        }
        String value = raw.get(name);
        assertNotNull(name, value);
        if (value.startsWith("@color/")) {
            value = resolve(value.substring("@color/".length()), raw, stack);
        }
        stack.remove(name);
        return value;
    }

    static List<Point> parsePath(String path) {
        List<Point> points = new ArrayList<>();
        double cx = 0;
        double cy = 0;
        double sx = 0;
        double sy = 0;
        char command = 0;
        int index = 0;
        while (index < path.length()) {
            char ch = path.charAt(index);
            if (Character.isLetter(ch)) {
                command = ch;
                index++;
                if (command == 'Z' || command == 'z') {
                    cx = sx;
                    cy = sy;
                    points.add(new Point(cx, cy));
                }
                continue;
            }
            if (Character.isWhitespace(ch) || ch == ',') {
                index++;
                continue;
            }
            double[] number = readNumber(path, index);
            index = (int) number[1];
            switch (command) {
                case 'M':
                    cx = number[0];
                    number = readNumber(path, index);
                    index = (int) number[1];
                    cy = number[0];
                    sx = cx;
                    sy = cy;
                    points.add(new Point(cx, cy));
                    command = 'L';
                    break;
                case 'L':
                    cx = number[0];
                    number = readNumber(path, index);
                    index = (int) number[1];
                    cy = number[0];
                    points.add(new Point(cx, cy));
                    break;
                case 'H':
                    cx = number[0];
                    points.add(new Point(cx, cy));
                    break;
                case 'V':
                    cy = number[0];
                    points.add(new Point(cx, cy));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported path command " + command);
            }
        }
        assertTrue(points.size() >= 3);
        return points;
    }

    private static double[] readNumber(String path, int index) {
        while (index < path.length()) {
            char ch = path.charAt(index);
            if (ch == ',' || Character.isWhitespace(ch)) {
                index++;
                continue;
            }
            break;
        }
        int start = index;
        if (path.charAt(index) == '-' || path.charAt(index) == '+') {
            index++;
        }
        while (index < path.length()) {
            char ch = path.charAt(index);
            if ((ch >= '0' && ch <= '9') || ch == '.') {
                index++;
                continue;
            }
            break;
        }
        return new double[] {Double.parseDouble(path.substring(start, index)), index};
    }

    static final class Point {
        final double x;
        final double y;

        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
}
