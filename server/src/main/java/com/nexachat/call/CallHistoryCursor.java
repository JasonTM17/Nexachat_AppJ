package com.nexachat.call;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

record CallHistoryCursor(Instant startedAt, UUID callId) {

    static String encode(CallRecord record) {
        String value = record.getStartedAt() + "|" + record.getCallId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static CallHistoryCursor decode(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] pieces = decoded.split("\\|", -1);
            if (pieces.length != 2) {
                throw new IllegalArgumentException("bad cursor shape");
            }
            return new CallHistoryCursor(Instant.parse(pieces[0]), UUID.fromString(pieces[1]));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw CallException.validation("Invalid call history cursor");
        }
    }
}
