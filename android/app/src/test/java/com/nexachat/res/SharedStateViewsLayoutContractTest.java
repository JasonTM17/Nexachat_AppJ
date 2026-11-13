package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Shared loading/empty/error views must inflate their v2 templates and expose polite live regions.
 */
public final class SharedStateViewsLayoutContractTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

    private File resDir;
    private File javaDir;

    @Before
    public void setUp() {
        File[] resCandidates = new File[] {
                new File("src/main/res"),
                new File("app/src/main/res"),
                new File("android/app/src/main/res"),
                new File("../app/src/main/res")
        };
        for (File candidate : resCandidates) {
            if (candidate.isDirectory() && new File(candidate, "values/colors_nexachat.xml").isFile()) {
                resDir = candidate;
                javaDir = new File(candidate.getParentFile().getParentFile(), "java");
                if (!javaDir.isDirectory()) {
                    javaDir = new File("android/app/src/main/java");
                }
                return;
            }
        }
        throw new IllegalStateException("Could not locate res directory");
    }

    @Test
    public void statusTextViewsDeclarePoliteLiveRegion() throws Exception {
        Map<String, String> layoutToStatusId = new LinkedHashMap<>();
        layoutToStatusId.put("view_loading_state.xml", "loadingLabel");
        layoutToStatusId.put("view_empty_state.xml", "emptyMessage");
        layoutToStatusId.put("view_error_state.xml", "errorMessage");

        for (Map.Entry<String, String> entry : layoutToStatusId.entrySet()) {
            Document doc = parse(new File(resDir, "layout/" + entry.getKey()));
            Element status = findElementById(doc.getDocumentElement(), entry.getValue());
            assertNotNull(entry.getKey() + " must declare @" + entry.getValue(), status);
            assertEquals(entry.getKey() + " @" + entry.getValue()
                            + " must use android:accessibilityLiveRegion=\"polite\"",
                    "polite", status.getAttributeNS(NS_ANDROID, "accessibilityLiveRegion"));
        }
    }

    @Test
    public void iconsAreDecorativeAndRetryIsNamed() throws Exception {
        Document emptyDoc = parse(new File(resDir, "layout/view_empty_state.xml"));
        Element emptyIcon = findElementById(emptyDoc.getDocumentElement(), "emptyIcon");
        assertNotNull(emptyIcon);
        assertEquals("no", emptyIcon.getAttributeNS(NS_ANDROID, "importantForAccessibility"));

        Document errorDoc = parse(new File(resDir, "layout/view_error_state.xml"));
        Element errorIcon = findElementById(errorDoc.getDocumentElement(), "errorIcon");
        assertNotNull(errorIcon);
        assertEquals("no", errorIcon.getAttributeNS(NS_ANDROID, "importantForAccessibility"));

        Element retryButton = findElementById(errorDoc.getDocumentElement(), "retryButton");
        assertNotNull(retryButton);
        assertEquals("@string/action_retry", retryButton.getAttributeNS(NS_ANDROID, "text"));
    }

    @Test
    public void viewClassesInflateTheirTemplates() throws Exception {
        assertInflates("LoadingStateView.java", "R.layout.view_loading_state", "R.id.loadingLabel");
        assertInflates("EmptyStateView.java", "R.layout.view_empty_state", "R.id.emptyMessage");
        assertInflates("ErrorStateView.java", "R.layout.view_error_state", "R.id.errorMessage");
    }

    private void assertInflates(String classFile, String layoutRef, String idRef) throws Exception {
        String source = readJava(classFile);
        assertTrue(classFile + " must inflate " + layoutRef, source.contains(layoutRef));
        assertTrue(classFile + " must bind " + idRef, source.contains(idRef));
    }

    private String readJava(String name) throws Exception {
        File direct = new File(javaDir, "com/nexachat/core/ui/components/" + name);
        if (direct.isFile()) {
            return new String(Files.readAllBytes(direct.toPath()), StandardCharsets.UTF_8);
        }
        File[] candidates = new File[] {
                new File("src/main/java/com/nexachat/core/ui/components/" + name),
                new File("app/src/main/java/com/nexachat/core/ui/components/" + name),
                new File("android/app/src/main/java/com/nexachat/core/ui/components/" + name)
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Missing " + name);
    }

    private static Element findElementById(Node node, String idSuffix) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) node;
            String id = el.getAttributeNS(NS_ANDROID, "id");
            if (id != null && id.endsWith("/" + idSuffix)) {
                return el;
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Element match = findElementById(children.item(i), idSuffix);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }
}
