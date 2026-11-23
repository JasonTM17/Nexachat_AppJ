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

    @Test
    public void topAnchoredSubmitScrollsBackToPositionZero() throws Exception {
        String source = readUtf8(locateJavaFile(
                "com/nexachat/feature/inbox/InboxFragment.java"));
        int methodStart = source.indexOf("private void submitRowsPreservingPosition(");
        int methodEnd = source.indexOf("private void maybeLoadMore()", methodStart);
        String method = source.substring(methodStart, methodEnd);

        // Undoing an archive re-inserts the row at position 0. Re-anchoring to
        // the displaced first item keeps the restored row one row-height above
        // the viewport — submitted but never laid out. When the list was at the
        // top the submit must scroll to position 0 instead.
        assertTrue("a list already at the top must scroll to position 0 so a "
                        + "row restored above the previous anchor becomes visible",
                method.contains("scrollToPositionWithOffset(0,"));
        assertTrue("the scroll-to-top must be gated on the pre-submit top position",
                method.contains("atTop")
                        && method.indexOf("atTop") < method.indexOf("adapter.submitList(rows"));
    }

    @Test
    public void topAnchoredSubmitExpandsTheCollapsedAppBar() throws Exception {
        String source = readUtf8(locateJavaFile(
                "com/nexachat/feature/inbox/InboxFragment.java"));
        int methodStart = source.indexOf("private void submitRowsPreservingPosition(");
        int methodEnd = source.indexOf("private void maybeLoadMore()", methodStart);
        String method = source.substring(methodStart, methodEnd);

        // The inbox header collapses on scroll. When a restored top row pulls
        // the list back to position 0 the app bar must expand too, or the row
        // returns while the filters stay hidden until the next scroll.
        int scrollToTop = method.indexOf("if (scrollToTop)");
        int expanded = method.indexOf("appBar.setExpanded(true");
        assertTrue("the scroll-to-top path must expand the collapsing app bar "
                        + "so a restored row and the header return together",
                scrollToTop >= 0 && expanded > scrollToTop);
    }

    @Test
    public void focusedHeaderControlExpandsTheCollapsedAppBar() throws Exception {
        String source = readUtf8(locateJavaFile(
                "com/nexachat/feature/inbox/InboxFragment.java"));
        int methodStart = source.indexOf("private void expandAppBarForHeaderFocus(");
        int methodEnd = source.indexOf("private void applyFilterChipRowLayout(", methodStart);
        String method = source.substring(methodStart, methodEnd);

        // While the header is collapsed a TalkBack or keyboard user can still
        // land on a clipped filter chip; focus must re-expand the app bar or
        // the focused control stays hidden under the conversation list.
        assertTrue("focusing a header control must re-expand the app bar",
                method.contains("addOnGlobalFocusChangeListener")
                        && method.contains("appBar.setExpanded(true"));
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
