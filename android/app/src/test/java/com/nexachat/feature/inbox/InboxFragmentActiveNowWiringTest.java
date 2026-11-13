package com.nexachat.feature.inbox;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class InboxFragmentActiveNowWiringTest {

    @Test
    public void inboxFragment_delegatesActiveNowStripToBinder() throws Exception {
        String source = readUtf8(locateJavaFile(
                "com/nexachat/feature/inbox/InboxFragment.java"));
        assertTrue(source.contains("ActiveNowStripBinder"));
        assertTrue(source.contains("activeNowStripBinder.bind("));
        assertFalse("Active Now must not wipe the strip on every render",
                source.contains("activeNowStrip.removeAllViews()"));
    }

    private static File locateJavaFile(String relativePath) {
        File[] candidates = {
                new File("src/main/java/" + relativePath),
                new File("app/src/main/java/" + relativePath),
                new File("android/app/src/main/java/" + relativePath)
        };
        for (File file : candidates) {
            if (file.isFile()) {
                return file;
            }
        }
        throw new IllegalStateException("Missing " + relativePath);
    }

    private static String readUtf8(File file) throws Exception {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
            }
        }
        return body.toString();
    }
}
