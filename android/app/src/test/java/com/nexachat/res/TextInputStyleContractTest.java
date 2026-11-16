package com.nexachat.res;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Text-field style contract: every form field uses the shared filled
 * {@code Widget.NexaChat.TextInputLayout} routed through the
 * {@code textInputStyle} theme attribute. A programmatic
 * {@code TextInputLayout} must not pin Material's outlined-box style, and
 * layouts must not reference the raw {@code Widget.Material3.TextInputLayout.*}
 * styles directly; the forward sheet's borderless search pill is the one
 * documented exception.
 */
public final class TextInputStyleContractTest {

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
                break;
            }
        }
        if (resDir == null) {
            throw new IllegalStateException("Could not locate res directory");
        }
        File[] javaCandidates = new File[] {
                new File("src/main/java"),
                new File("app/src/main/java"),
                new File("android/app/src/main/java"),
                new File("../app/src/main/java")
        };
        for (File candidate : javaCandidates) {
            if (candidate.isDirectory()) {
                javaDir = candidate;
                return;
            }
        }
        throw new IllegalStateException("Could not locate java directory");
    }

    @Test
    public void noProgrammaticTextInputLayoutPinsTheOutlinedStyle() throws Exception {
        List<String> failures = new ArrayList<>();
        for (File source : javaFiles(javaDir)) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            if (text.contains("textInputOutlinedStyle")) {
                failures.add(relativePath(source)
                        + " constructs a TextInputLayout with textInputOutlinedStyle;"
                        + " use the themed textInputStyle (filled NexaChat box)");
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void layoutsDoNotUseRawMaterialTextInputStyles() throws Exception {
        List<String> failures = new ArrayList<>();
        for (File layout : layoutFiles()) {
            if (layout.getName().equals("dialog_forward.xml")) {
                continue;
            }
            String text = new String(Files.readAllBytes(layout.toPath()), StandardCharsets.UTF_8);
            if (text.contains("Widget.Material3.TextInputLayout.OutlinedBox")) {
                failures.add(relativePath(layout)
                        + " uses Widget.Material3.TextInputLayout.OutlinedBox;"
                        + " use Widget.NexaChat.TextInputLayout or a themed search pill");
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    private List<File> javaFiles(File dir) {
        List<File> files = new ArrayList<>();
        collect(dir, files, ".java");
        return files;
    }

    private List<File> layoutFiles() {
        List<File> files = new ArrayList<>();
        File[] dirs = resDir.listFiles();
        if (dirs == null) {
            return files;
        }
        for (File dir : dirs) {
            if (dir.isDirectory()
                    && (dir.getName().equals("layout") || dir.getName().startsWith("layout-"))) {
                collect(dir, files, ".xml");
            }
        }
        return files;
    }

    private void collect(File dir, List<File> files, String suffix) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, files, suffix);
            } else if (child.getName().endsWith(suffix)) {
                files.add(child);
            }
        }
    }

    private String relativePath(File file) {
        return file.getParentFile().getName() + "/" + file.getName();
    }
}
