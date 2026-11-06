package com.nexachat.call;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CallHistoryCursorTest {

    @Test
    void roundTripsStartedAtAndCallId() {
        UUID callId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID callerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID calleeId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Instant startedAt = Instant.parse("2026-11-06T10:15:30Z");
        CallSession session = new CallSession(
                callId,
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                callerId,
                calleeId,
                CallState.RINGING,
                null,
                startedAt,
                null,
                null,
                null);

        CallHistoryCursor decoded = CallHistoryCursor.decode(CallHistoryCursor.encode(CallRecord.from(session)));

        assertEquals(startedAt, decoded.startedAt());
        assertEquals(callId, decoded.callId());
    }

    @Test
    void rejectsACursorThatIsNotATimestampAndId() {
        CallException exception = assertThrows(CallException.class, () -> CallHistoryCursor.decode("not-a-cursor"));
        assertEquals(CallException.Code.VALIDATION_FAILED, exception.getCode());
    }
}
