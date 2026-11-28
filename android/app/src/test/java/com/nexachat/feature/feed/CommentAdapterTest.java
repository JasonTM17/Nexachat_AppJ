package com.nexachat.feature.feed;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CommentAdapterTest {

    @Test
    public void commentTalkBackDescriptionIncludesTimeAndEditedStatus() {
        assertEquals("An, 5 min. ago (edited): Nice photo",
                CommentAdapter.formatContentDescription(
                        "An", "5 min. ago", "(edited)", "Nice photo",
                        "%1$s: %2$s", "%1$s, %2$s: %3$s"));
        assertEquals("An, 5 min. ago: Nice photo",
                CommentAdapter.formatContentDescription(
                        "An", "5 min. ago", null, "Nice photo",
                        "%1$s: %2$s", "%1$s, %2$s: %3$s"));
        assertEquals("An, (edited): Nice photo",
                CommentAdapter.formatContentDescription(
                        "An", "", "(edited)", "Nice photo",
                        "%1$s: %2$s", "%1$s, %2$s: %3$s"));
        assertEquals("An: Nice photo",
                CommentAdapter.formatContentDescription(
                        "An", "", null, "Nice photo",
                        "%1$s: %2$s", "%1$s, %2$s: %3$s"));
    }
}
