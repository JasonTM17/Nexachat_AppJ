package com.nexachat.summary;

public class SummaryTooFewMessagesException extends RuntimeException {

    private final transient SummaryScope scope;
    private final int available;
    private final int required;
    private final transient SummaryPeriod period;

    public SummaryTooFewMessagesException() {
        this(null, 0, 5, null);
    }

    public SummaryTooFewMessagesException(SummaryScope scope, int available, int required, SummaryPeriod period) {
        super("At least " + required + " usable messages are required");
        this.scope = scope;
        this.available = available;
        this.required = required;
        this.period = period;
    }

    public SummaryScope scope() {
        return scope;
    }

    public int available() {
        return available;
    }

    public int required() {
        return required;
    }

    public SummaryPeriod period() {
        return period;
    }
}
