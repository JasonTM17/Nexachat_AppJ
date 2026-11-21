package com.nexachat.core.ui.components;

import android.view.View;
import androidx.annotation.NonNull;

/**
 * Accessibility rules for label-free structural containers (list and filter
 * scroll surfaces).
 *
 * {@code ScrollView}/{@code HorizontalScrollView}/{@code TabLayout} (a
 * HorizontalScrollView subclass) and androidx {@code RecyclerView} mark
 * themselves view-focusable in their constructors. That focusability never
 * gives TalkBack a useful stop — the meaningful targets are the rows, chips
 * and tabs inside — but it does surface the container itself as an unlabeled
 * interactive node to the a11y sweep's flat uiautomator dump (issue #254's
 * {@code conversationList}/{@code inboxFilterScroll}/{@code messageList}
 * findings). The dump keeps reading the container; the fix is to stop it being
 * an interactive node at all: containers stay visible to a11y services (their
 * scroll semantics and children are unaffected) but stop being focus targets,
 * exactly the {@code mentionPicker} precedent in {@code ChatFragment}.
 */
public final class A11yContainerRules {

    private A11yContainerRules() {
    }

    /**
     * Drops a structural container from the focus-target set without hiding it
     * from a11y services or labeling it: {@code setFocusable(false)} also
     * clears touch-mode focusability, and importance stays untouched so
     * TalkBack keeps the container's scroll actions and its children.
     */
    public static void suppressFocusTarget(@NonNull View container) {
        container.setFocusable(false);
        container.setFocusableInTouchMode(false);
    }
}
