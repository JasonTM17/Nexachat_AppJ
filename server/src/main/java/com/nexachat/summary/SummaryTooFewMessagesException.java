package com.nexachat.summary;

public class SummaryTooFewMessagesException extends RuntimeException {

    public SummaryTooFewMessagesException() {
        super("At least 5 usable messages are required");
    }
}
