package com.nexachat.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Square-button contract: every button in the app renders with 0dp corners.
 * The square shape lives in {@code ShapeAppearance.NexaChat.Button}, applied by
 * the {@code Widget.NexaChat.Button*} styles the themes route through
 * {@code materialButtonStyle} and friends. A layout must not re-round a button
 * with {@code app:cornerRadius} or a raw {@code Widget.Material3.Button.*} style,
 * and icon buttons use the bounded (square) ripple, never the borderless circle.
 */
public final class ButtonShapeContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";
    private static final String MATERIAL_BUTTON =
            "com.google.android.material.button.MaterialButton";
    private static final String SQUARE_SHAPE = "@style/ShapeAppearance.NexaChat.Button";

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
    public void buttonShapeAppearanceIsSquare() throws Exception {
        Element shape = style(parse("values/shapes.xml"), "ShapeAppearance.NexaChat.Button");
        assertEquals("0dp", item(shape, "cornerSize"));
    }

    @Test
    public void nexaChatButtonStylesPinTheSquareShape() throws Exception {
        Document styles = parse("values/styles_nexachat.xml");
        NodeList all = styles.getElementsByTagName("style");
        int buttonStyles = 0;
        for (int i = 0; i < all.getLength(); i++) {
            Element style = (Element) all.item(i);
            String name = style.getAttribute("name");
            if (!name.startsWith("Widget.NexaChat.Button")
                    && !name.startsWith("Widget.NexaChat.FloatingActionButton")) {
                continue;
            }
            buttonStyles++;
            assertEquals(name + " must pin the square shape",
                    SQUARE_SHAPE, item(style, "shapeAppearance"));
            assertEquals(name + " must not override the square corners",
                    "", item(style, "cornerRadius"));
        }
        assertTrue("no NexaChat button styles found", buttonStyles >= 5);
    }

    @Test
    public void viewsThemeRoutesEveryButtonKindThroughNexaChatStyles() throws Exception {
        Element theme = style(parse("values/styles_nexachat.xml"), "Theme.NexaChat.Views");
        assertEquals("@style/Widget.NexaChat.Button", item(theme, "materialButtonStyle"));
        assertEquals("@style/Widget.NexaChat.Button.Outlined",
                item(theme, "materialButtonOutlinedStyle"));
        assertEquals("@style/Widget.NexaChat.Button.Text",
                item(theme, "borderlessButtonStyle"));
        assertEquals("@style/Widget.NexaChat.Button.Icon",
                item(theme, "materialIconButtonStyle"));
        assertEquals("@style/Widget.NexaChat.Button.Text.Dialog",
                item(theme, "buttonBarPositiveButtonStyle"));
        assertEquals("@style/Widget.NexaChat.Button.Text.Dialog.Flush",
                item(theme, "buttonBarNegativeButtonStyle"));
        assertEquals("@style/Widget.NexaChat.Button.Text.Dialog",
                item(theme, "buttonBarNeutralButtonStyle"));
        assertEquals("@style/Widget.NexaChat.FloatingActionButton",
                item(theme, "floatingActionButtonStyle"));
    }

    @Test
    public void callThemeRoutesMaterialButtonsThroughNexaChatStyles() throws Exception {
        for (String dir : new String[] {"values", "values-night"}) {
            Element theme = style(parse(dir + "/styles_call.xml"), "Theme.NexaChat.Call");
            assertEquals(dir + " materialButtonStyle",
                    "@style/Widget.NexaChat.Button", item(theme, "materialButtonStyle"));
        }
    }

    @Test
    public void noLayoutReroundsAMaterialButton() throws Exception {
        List<String> failures = new ArrayList<>();
        for (File layout : layoutFiles()) {
            Document doc = parse(layout);
            NodeList nodes = doc.getElementsByTagName(MATERIAL_BUTTON);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element button = (Element) nodes.item(i);
                String path = relativePath(layout);
                if (!app(button, "cornerRadius").isEmpty()) {
                    failures.add(path + " sets app:cornerRadius on a MaterialButton");
                }
                String style = button.getAttribute("style");
                if (style.startsWith("@style/Widget.Material3.Button")) {
                    failures.add(path + " bypasses the NexaChat button styles: " + style);
                }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void iconButtonsKeepSquareBoundedRipples() throws Exception {
        List<String> failures = new ArrayList<>();
        for (File layout : layoutFiles()) {
            Document doc = parse(layout);
            NodeList nodes = doc.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);
                if (android(node, "background").endsWith("selectableItemBackgroundBorderless")
                        || android(node, "foreground")
                                .endsWith("selectableItemBackgroundBorderless")) {
                    failures.add(relativePath(layout) + " uses a circular borderless ripple");
                }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void sendButtonDrawableIsASquareRipple() throws Exception {
        Document send = parse("drawable/bg_send_button.xml");
        assertEquals("ripple", send.getDocumentElement().getTagName());
        NodeList shapes = send.getElementsByTagName("shape");
        assertTrue("send button needs a visible square shape", shapes.getLength() > 0);
        for (int i = 0; i < shapes.getLength(); i++) {
            Element shape = (Element) shapes.item(i);
            assertEquals("rectangle", shape.getAttributeNS(ANDROID, "shape"));
        }
    }

    private File[] layoutFiles() {
        List<File> files = new ArrayList<>();
        File[] dirs = resDir.listFiles();
        if (dirs == null) {
            throw new IllegalStateException("Could not list " + resDir);
        }
        for (File dir : dirs) {
            if (!dir.isDirectory()
                    || !(dir.getName().equals("layout") || dir.getName().startsWith("layout-"))) {
                continue;
            }
            File[] layouts = dir.listFiles((d, name) -> name.endsWith(".xml"));
            if (layouts != null) {
                files.addAll(List.of(layouts));
            }
        }
        return files.toArray(new File[0]);
    }

    private String relativePath(File file) {
        return file.getParentFile().getName() + "/" + file.getName();
    }

    private Element style(Document doc, String name) {
        NodeList nodes = doc.getElementsByTagName("style");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (name.equals(node.getAttribute("name"))) {
                return node;
            }
        }
        throw new AssertionError("Missing style " + name + " in " + doc.getDocumentURI());
    }

    private static String item(Element style, String name) {
        NodeList items = style.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if (name.equals(item.getAttribute("name"))) {
                return item.getTextContent().trim();
            }
        }
        return "";
    }

    private Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }

    private Document parse(String relative) throws Exception {
        return parse(new File(resDir, relative));
    }

    private static String android(Element node, String name) {
        return node.getAttributeNS(ANDROID, name);
    }

    private static String app(Element node, String name) {
        return node.getAttributeNS(APP, name);
    }
}
