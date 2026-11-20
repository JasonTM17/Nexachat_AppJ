package com.nexachat.feature.chat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class ChatRowRenderInputsTest {

    private TimeZone previousTimeZone;

    @Before
    public void useUtcForDayBoundaries() {
        previousTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After
    public void restoreTimeZone() {
        TimeZone.setDefault(previousTimeZone);
    }

    private static List<ChatMessage> messages(String... ids) {
        long base = Instant.parse("2025-05-01T12:00:00Z").toEpochMilli();
        return Arrays.stream(ids)
                .map(id -> new ChatMessage(
                        id,
                        "conversation",
                        1L,
                        "sender",
                        "Sender",
                        "TEXT",
                        "hello",
                        null,
                        null,
                        null,
                        base,
                        null,
                        DeliveryStatus.SENT,
                        false,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        null,
                        null))
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    public void identicalSnapshotsAreSkippedUntilAnInputChanges() {
        ChatRowRenderInputs memo = new ChatRowRenderInputs();
        List<ChatMessage> messages = messages("a", "b");
        long now = Instant.parse("2025-05-01T15:00:00Z").toEpochMilli();

        assertTrue("first submission always builds", memo.requiresRebuild(
                messages, false, 1L, 0L, null, -1, 0, Set.of(), now));
        assertFalse("an identical emission must not rebuild", memo.requiresRebuild(
                messages, false, 1L, 0L, null, -1, 0, Set.of(), now));
        assertFalse("a value-equal fresh list instance must not rebuild",
                memo.requiresRebuild(
                        messages("a", "b"), false, 1L, 0L, null, -1, 0, Set.of(), now));

        assertTrue("new rows must rebuild",
                memo.requiresRebuild(messages("a", "b", "c"), false, 1L, 0L, null, -1, 0, Set.of(), now));
        assertTrue("group flag must rebuild",
                memo.requiresRebuild(messages("a", "b", "c"), true, 1L, 0L, null, -1, 0, Set.of(), now));
        assertTrue("peer read watermark must rebuild",
                memo.requiresRebuild(messages("a", "b", "c"), true, 2L, 0L, null, -1, 0, Set.of(), now));
        assertTrue("peer delivered watermark must rebuild",
                memo.requiresRebuild(messages("a", "b", "c"), true, 2L, 1L, null, -1, 0, Set.of(), now));
        assertTrue("pulse target must rebuild",
                memo.requiresRebuild(messages("a", "b", "c"), true, 2L, 1L, "a", -1, 0, Set.of(), now));
        assertTrue("divider placement must rebuild",
                memo.requiresRebuild(messages("a", "b", "c"), true, 2L, 1L, "a", 1, 2, Set.of(), now));
        assertTrue("divider count must rebuild",
                memo.requiresRebuild(messages("a", "b", "c"), true, 2L, 1L, "a", 1, 3, Set.of(), now));
        assertTrue("starred set must rebuild",
                memo.requiresRebuild(messages("a", "b", "c"), true, 2L, 1L, "a", 1, 3, Set.of("a"), now));
        assertFalse("returning to the recorded state skips again",
                memo.requiresRebuild(messages("a", "b", "c"), true, 2L, 1L, "a", 1, 3, Set.of("a"), now));
    }

    @Test
    public void midnightRolloverRebuildsSeparatorLabels() {
        ChatRowRenderInputs memo = new ChatRowRenderInputs();
        List<ChatMessage> messages = messages("a");
        long beforeMidnight = Instant.parse("2025-05-01T23:59:00Z").toEpochMilli();
        long afterMidnight = Instant.parse("2025-05-02T00:01:00Z").toEpochMilli();

        assertTrue(memo.requiresRebuild(messages, false, 0L, 0L, null, -1, 0, Set.of(), beforeMidnight));
        assertFalse("same day, same inputs: no rebuild",
                memo.requiresRebuild(messages, false, 0L, 0L, null, -1, 0, Set.of(), beforeMidnight));
        assertTrue("a new local day must rebuild Today/Yesterday labels",
                memo.requiresRebuild(messages, false, 0L, 0L, null, -1, 0, Set.of(), afterMidnight));
    }

    @Test
    public void invalidatedMemosRebuildIdenticalInputs() {
        ChatRowRenderInputs memo = new ChatRowRenderInputs();
        List<ChatMessage> messages = messages("a");
        long now = Instant.parse("2025-05-01T12:00:00Z").toEpochMilli();

        assertTrue(memo.requiresRebuild(messages, false, 0L, 0L, null, -1, 0, Set.of(), now));
        assertFalse(memo.requiresRebuild(messages, false, 0L, 0L, null, -1, 0, Set.of(), now));

        memo.invalidate();
        assertTrue("adapter content replaced elsewhere must be rebuilt",
                memo.requiresRebuild(messages, false, 0L, 0L, null, -1, 0, Set.of(), now));
    }
}
