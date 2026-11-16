package com.nexachat.data;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class ConversationSummaryEqualsTest {

    @Test
    public void identicalSummariesAreEqual() {
        ConversationSummary left = summary(false, false);
        ConversationSummary right = summary(false, false);
        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    public void e2eeEnabledFlipBreaksEquality() {
        ConversationSummary plain = summary(false, false);
        ConversationSummary e2ee = summary(true, false);
        assertNotEquals(plain, e2ee);
        assertNotEquals(plain.hashCode(), e2ee.hashCode());
    }

    @Test
    public void lastMessageEncryptedFlipBreaksEquality() {
        ConversationSummary plain = summary(false, false);
        ConversationSummary encrypted = summary(false, true);
        assertNotEquals(plain, encrypted);
        assertNotEquals(plain.hashCode(), encrypted.hashCode());
    }

    private static ConversationSummary summary(boolean e2eeEnabled, boolean lastMessageEncrypted) {
        return new ConversationSummary(
                "c-1", "DIRECT", null, "hi", "Peer", 1_000L, 1L, 0, 0L,
                "Peer", null, null, 0L, 0L, null, "peer-1", false, null,
                false, null, false, false, false, null, List.of(),
                e2eeEnabled, lastMessageEncrypted, "TEXT");
    }
}
