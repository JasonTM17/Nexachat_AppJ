package com.nexachat.summary;

public class SummaryInvalidTimeZoneException extends RuntimeException {

    public SummaryInvalidTimeZoneException() {
        super("timeZone must be an IANA zone id or a UTC offset");
    }
}
