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
        int methodEnd = source.indexOf("private void expandAppBarForKeyboardTraversal(", methodStart);
        String method = source.substring(methodStart, methodEnd);

        // While the header is collapsed a TalkBack or keyboard user can still
        // land on a clipped filter chip; focus must re-expand the app bar or
        // the focused control stays hidden under the conversation list.
        assertTrue("focusing a header control must re-expand the app bar",
                method.contains("addOnGlobalFocusChangeListener")
                        && method.contains("appBar.setExpanded(true)"));
    }

    @Test
    public void dpadUpPastTopRowExpandsTheCollapsedAppBar() throws Exception {
        String source = readUtf8(locateJavaFile(
                "com/nexachat/feature/inbox/InboxFragment.java"));
        int methodStart = source.indexOf("private void expandAppBarForKeyboardTraversal(");
        int methodEnd = source.indexOf("private void applyFilterChipRowLayout(", methodStart);
        String method = source.substring(methodStart, methodEnd);

        // A fully-collapsed header is pruned from the focus tree: DPAD_UP on
        // the top row has no target and arrives as an unhandled key — expand
        // and hand focus to the search pill so keyboard users always have a
        // path into the header controls (verified on API-36 emulator).
        assertTrue("DPAD_UP past the top row must re-expand the collapsed app bar",
                method.contains("addOnUnhandledKeyEventListener")
                        && method.contains("KEYCODE_DPAD_UP")
                        && method.contains("appBar.setExpanded(true)")
                        && method.contains("inboxSearchPill"));

        // AppBarLayout is a keyboardNavigationCluster with
        // touchscreenBlocksFocus: requestFocus on any header child that is not
        // focusableInTouchMode returns false, so the search pill could never
        // receive the transferred focus until the block was cleared.
        assertTrue("the app bar's touchscreen focus block must be cleared "
                        + "or no header control can ever take focus",
                method.contains("setTouchscreenBlocksFocus(false)"));

        // The listener must only consume the real dead end: while the bar is
        // expanded, ordinary focus search already reaches the chips/pill, and
        // mid-list rows still have a row above — stealing those presses froze
        // all upward list navigation (device-verified regression).
        assertTrue("DPAD_UP must not be consumed when ordinary focus search "
                        + "has an upward target",
                method.contains("focusSearch(View.FOCUS_UP)")
                        && method.contains("findNextFocus")
                        && method.contains("return false"));
    }

    @Test
    public void headerFocusTraversalExitsDownwardAndForward() throws Exception {
        String source = readUtf8(locateJavaFile(
                "com/nexachat/feature/inbox/InboxFragment.java"));
        String layout = readUtf8(locateResFile("layout/fragment_inbox.xml"));
        int methodStart = source.indexOf(
                "private void expandAppBarForKeyboardTraversal(");
        int methodEnd = source.indexOf(
                "private void applyFilterChipRowLayout(", methodStart);
        String method = source.substring(methodStart, methodEnd);

        // On the verified platform build the directional focus search never
        // produces a downward target out of the toolbar and the framework's
        // user-specified next-focus lookup fails the same way. The unhandled
        // DOWN listener on the app bar re-resolves the wired nextFocusDownId
        // manually — strictly as a dead-end fallback.
        assertTrue("DPAD_DOWN dead-ends inside the app bar must resolve the "
                        + "wired nextFocusDown target",
                method.contains("KEYCODE_DPAD_DOWN")
                        && method.contains("getNextFocusDownId")
                        && method.contains("requestFocus"));

        // The app bar cluster wraps TAB within itself; the views closing a
        // forward segment need an explicit key path because the platform
        // never fires nextFocusForward resolution for them.
        assertTrue("TAB at a segment boundary must perform the wired "
                        + "nextFocusForward jump",
                method.contains("KEYCODE_TAB")
                        && method.contains("getNextFocusForwardId")
                        && method.contains("setOnKeyListener"));

        // The declarative focus chain the handlers resolve: toolbar row ->
        // search pill -> first filter chip -> conversation list.
        assertTrue("toolbar controls must declare nextFocusDown to the pill",
                layout.contains("inboxSearchPill")
                        && layout.contains("nextFocusDown=\"@id/inboxSearchPill\"")
                        && layout.contains("nextFocusDown=\"@id/filterAllChip\"")
                        && layout.contains("nextFocusDown=\"@id/conversationList\"")
                        && layout.contains("nextFocusForward=\"@id/inboxSearchPill\"")
                        && layout.contains("nextFocusForward=\"@id/conversationList\""));
    }

    private static File locateResFile(String relativePath) {
        File[] candidates = {
                new File("src/main/res/" + relativePath),
                new File("app/src/main/res/" + relativePath),
                new File("android/app/src/main/res/" + relativePath)
        };
        for (File file : candidates) {
            if (file.isFile()) {
                return file;
            }
        }
        throw new IllegalStateException("Missing " + relativePath);
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
