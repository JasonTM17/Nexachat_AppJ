package com.nexachat.data;

import com.squareup.moshi.Moshi;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;

/** ISO instant parsing and JWT payload helpers shared from Kotlin repositories. */
public final class TimeAndJwt {

    private TimeAndJwt() {}

    public static Long isoToEpochMsOrNull(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception ignored) {
            // try OffsetDateTime next
        }
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static long isoToEpochMs(String iso) {
        Long parsed = isoToEpochMsOrNull(iso);
        return parsed != null ? parsed : System.currentTimeMillis();
    }

    public static String userIdFromJwt(String accessToken, Moshi moshi) {
        if (accessToken == null) {
            return null;
        }
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        final String payloadJson;
        try {
            payloadJson = new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        try {
            Map<?, ?> claims = moshi.adapter(Map.class).fromJson(payloadJson);
            if (claims == null) {
                return null;
            }
            Object sub = claims.get("sub");
            return sub instanceof String ? (String) sub : null;
        } catch (Exception e) {
            return null;
        }
    }
}
