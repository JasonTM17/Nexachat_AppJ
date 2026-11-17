package com.nexachat.summary;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Pattern;

/** Resolves the client's time zone and the local-day window used by the TODAY scope. */
public final class SummaryPeriods {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final int MAX_ZONE_ID_LENGTH = 64;
    private static final Pattern ZONE_ID_CHARACTERS = Pattern.compile("[A-Za-z0-9_+/:-]+");

    private SummaryPeriods() {
    }

    /** True when the client supplied no usable zone and Vietnam time applies. */
    public static boolean isDefaulted(String rawZone) {
        return rawZone == null || rawZone.isBlank();
    }

    /**
     * Parses a client zone. Absent or blank input means Vietnam time; anything that is not a
     * valid zone id or offset is rejected instead of silently replaced.
     */
    public static ZoneId resolveZone(String rawZone) {
        if (isDefaulted(rawZone)) {
            return DEFAULT_ZONE;
        }
        String candidate = rawZone.trim();
        if (candidate.length() > MAX_ZONE_ID_LENGTH || !ZONE_ID_CHARACTERS.matcher(candidate).matches()) {
            throw new SummaryInvalidTimeZoneException();
        }
        try {
            return ZoneId.of(candidate);
        } catch (DateTimeException ex) {
            throw new SummaryInvalidTimeZoneException();
        }
    }

    /**
     * The local day containing {@code now}: start of that day inclusive, start of the next
     * day exclusive. {@code atStartOfDay} keeps 23 and 25 hour days and skipped midnights correct.
     */
    public static SummaryPeriod today(Instant now, String rawZone) {
        ZoneId zone = resolveZone(rawZone);
        LocalDate date = now.atZone(zone).toLocalDate();
        return new SummaryPeriod(
                date,
                zone.getId(),
                date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant(),
                isDefaulted(rawZone));
    }
}
