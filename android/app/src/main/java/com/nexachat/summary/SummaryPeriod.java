package com.nexachat.summary;

/** The local calendar day a TODAY summary covers, as reported by the server. */
public final class SummaryPeriod {
    private final String date;
    private final String timeZone;
    private final String start;
    private final String end;
    private final boolean timeZoneDefaulted;

    SummaryPeriod() {
        this(null, null, null, null, false);
    }

    public SummaryPeriod(
            String date,
            String timeZone,
            String start,
            String end,
            boolean timeZoneDefaulted) {
        this.date = date;
        this.timeZone = timeZone;
        this.start = start;
        this.end = end;
        this.timeZoneDefaulted = timeZoneDefaulted;
    }

    /** ISO local date, for example {@code 2026-11-17}. */
    public String getDate() {
        return date;
    }

    /** IANA zone id the day was resolved in. */
    public String getTimeZone() {
        return timeZone;
    }

    public String getStart() {
        return start;
    }

    public String getEnd() {
        return end;
    }

    /** True when the request carried no zone and the server used Vietnam time. */
    public boolean isTimeZoneDefaulted() {
        return timeZoneDefaulted;
    }
}
