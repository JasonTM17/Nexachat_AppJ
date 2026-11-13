package com.nexachat.feature.inbox;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class InboxScrollSubmissionContractTest {

    @Test
    public void unchangedRowsDoNotSubmitOrRestoreScrollAnchor() throws Exception {
        String source = readUtf8(locateJavaFile(
                "com/nexachat/feature/inbox/InboxFragment.java"));
        int methodStart = source.indexOf("private void submitRowsPreservingPosition(");
        int methodEnd = source.indexOf("private void maybeLoadMore()", methodStart);
        String method = source.substring(methodStart, methodEnd);

        int unchangedRowsGuard = method.indexOf(
                "if (adapter.getCurrentList().equals(rows)) return;");
        int anchorCapture = method.indexOf("findFirstVisibleItemPosition()");
        int listSubmission = method.indexOf("adapter.submitList(rows");
        assertTrue("unchanged rows must be rejected before capturing the anchor",
                unchangedRowsGuard >= 0 && unchangedRowsGuard < anchorCapture);
        assertTrue("unchanged rows must be rejected before submitting a new list",
                unchangedRowsGuard < listSubmission);
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
