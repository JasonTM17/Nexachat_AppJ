package com.nexachat.data;

import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public final class IdempotencyUtilsTest {

    private static final UUID NAMESPACE_DNS =
            UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    @Test
    public void rfc4122TestVectorPythonOrg() {
        // RFC 4122 Appendix B UUIDv5 test vector for python.org under DNS namespace
        UUID uuid = IdempotencyUtils.uuidV5(NAMESPACE_DNS, "python.org");
        assertEquals("886313e1-3b8a-5372-9b90-0c9aee199e5d", uuid.toString());
        assertEquals(5, uuid.version());
        assertEquals(2, uuid.variant()); // RFC 4122 variant is 2
    }

    @Test
    public void deterministicReactionKeyDistinctForAddAndRemove() {
        String keyAdd = IdempotencyUtils.reactionIdempotencyKey("user-1", "msg-123", "🔥", true);
        String keyRemove = IdempotencyUtils.reactionIdempotencyKey("user-1", "msg-123", "🔥", false);
        String keyAddAgain = IdempotencyUtils.reactionIdempotencyKey("user-1", "msg-123", "🔥", "ADD");
        String keyRemoveAgain = IdempotencyUtils.reactionIdempotencyKey("user-1", "msg-123", "🔥", "REMOVE");

        assertNotNull(keyAdd);
        assertNotNull(keyRemove);
        assertEquals(keyAdd, keyAddAgain);
        assertEquals(keyRemove, keyRemoveAgain);
        assertNotEquals(keyAdd, keyRemove);
    }

    @Test
    public void deterministicPinKeyDistinctForPinAndUnpin() {
        String keyPin = IdempotencyUtils.pinIdempotencyKey("user-1", "conv-1", "msg-123", true);
        String keyUnpin = IdempotencyUtils.pinIdempotencyKey("user-1", "conv-1", "msg-123", false);

        assertNotNull(keyPin);
        assertNotNull(keyUnpin);
        assertNotEquals(keyPin, keyUnpin);

        String keyPinAgain = IdempotencyUtils.pinIdempotencyKey("user-1", "conv-1", "msg-123", true);
        assertEquals(keyPin, keyPinAgain);
    }
}
