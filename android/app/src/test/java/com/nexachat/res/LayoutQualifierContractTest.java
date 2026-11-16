package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertTrue;

/**
 * Every {@code android:id} declared in a base {@code layout/} file must also be
 * declared in each of its qualified variants ({@code layout-land/},
 * {@code layout-sw600dp/}, ...). Fragments bind the views of a screen
 * unconditionally: a variant that drops an id inflates it as null and the
 * first access crashes on the qualifier switch — the landscape chat crash on
 * rotation was exactly this class of bug. An id a variant intentionally omits
 * goes in {@link #ALLOWED_MISSING} with the reason that makes it safe.
 */
public final class LayoutQualifierContractTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

    /**
     * {@code "layout-<qualifier>/<file>"} → id → the one-line reason the base id
     * may stay absent from that variant. An entry whose id is present again is
     * stale and fails, so the list never hides a real gap.
     */
    private static final Map<String, Map<String, String>> ALLOWED_MISSING = Map.of();

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
    public void qualifiedLayoutsDeclareEveryBaseId() throws Exception {
        File baseDir = new File(resDir, "layout");
        StringBuilder failures = new StringBuilder();
        File[] dirs = resDir.listFiles();
        if (dirs == null) {
            throw new IllegalStateException("Could not list " + resDir);
        }
        for (File dir : dirs) {
            if (!dir.isDirectory() || !dir.getName().startsWith("layout-")) {
                continue;
            }
            File[] variants = dir.listFiles((d, name) -> name.endsWith(".xml"));
            if (variants == null) {
                continue;
            }
            for (File variant : variants) {
                File base = new File(baseDir, variant.getName());
                if (!base.isFile()) {
                    continue;
                }
                String variantPath = dir.getName() + "/" + variant.getName();
                Set<String> missing = idsIn(base);
                missing.removeAll(idsIn(variant));
                Map<String, String> allowed = ALLOWED_MISSING.getOrDefault(
                        variantPath, Map.of());
                for (String id : missing) {
                    String reason = allowed.get(id);
                    if (reason == null) {
                        failures.append(variantPath).append(" lacks @+id/")
                                .append(id).append(" declared in layout/")
                                .append(variant.getName()).append('\n');
                    }
                }
                Set<String> stale = new TreeSet<>(allowed.keySet());
                stale.retainAll(idsIn(variant));
                for (String id : stale) {
                    failures.append(variantPath).append(" declares @+id/")
                            .append(id).append(": drop the allow-list entry\n");
                }
            }
        }
        assertTrue(failures.toString(), failures.length() == 0);
    }

    private static Set<String> idsIn(File layout) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(layout);
        NodeList nodes = doc.getElementsByTagName("*");
        Set<String> ids = new TreeSet<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String id = element.getAttributeNS(NS_ANDROID, "id");
            if (id == null || id.isEmpty()) {
                id = element.getAttribute("android:id");
            }
            if (id != null && id.startsWith("@+id/")) {
                ids.add(id.substring("@+id/".length()));
            }
        }
        return ids;
    }
}
