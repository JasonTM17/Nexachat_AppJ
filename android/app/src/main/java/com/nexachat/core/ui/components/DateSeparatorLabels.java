package com.nexachat.core.ui.components;

import java.util.Objects;

/** Localized labels passed into date-separator formatting. */
public final class DateSeparatorLabels {

    private final String today;
    private final String yesterday;

    public DateSeparatorLabels(String today, String yesterday) {
        this.today = Objects.requireNonNull(today, "today");
        this.yesterday = Objects.requireNonNull(yesterday, "yesterday");
    }

    public String getToday() {
        return today;
    }

    public String getYesterday() {
        return yesterday;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DateSeparatorLabels labels)) {
            return false;
        }
        return today.equals(labels.today) && yesterday.equals(labels.yesterday);
    }

    @Override
    public int hashCode() {
        return Objects.hash(today, yesterday);
    }

    @Override
    public String toString() {
        return "DateSeparatorLabels(today=" + today + ", yesterday=" + yesterday + ")";
    }
}
