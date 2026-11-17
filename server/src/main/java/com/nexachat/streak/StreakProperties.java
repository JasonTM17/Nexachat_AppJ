package com.nexachat.streak;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Streak settings. A streak day is the calendar date of an instant in
 * {@code zone}; the default is UTC because no per-user time zone exists.
 */
@Validated
@ConfigurationProperties(prefix = "nexachat.streaks")
public class StreakProperties {

    /** Master switch for recording streak progress from sends and check-ins. */
    private boolean enabled = true;

    /** Whether the scheduled reminder job runs. */
    private boolean remindersEnabled = true;

    @NotNull
    private String zone = "UTC";

    /** Local hour (in {@code zone}) at which the daily reminder goes out. */
    @Min(0)
    @Max(23)
    private int reminderHour = 20;

    /** Smallest chain length that triggers a reminder. */
    @Min(1)
    private int reminderMinCount = 3;

    /** Users reminded per scan pass; the job loops until no candidate is left. */
    @Min(1)
    @Max(5_000)
    private int reminderBatchSize = 200;

    /** Upper bound on entries in the per-(scope,user) "already recorded today" cache. */
    @Min(100)
    private int cacheMaxEntries = 50_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isRemindersEnabled() { return remindersEnabled; }
    public void setRemindersEnabled(boolean remindersEnabled) { this.remindersEnabled = remindersEnabled; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
    public int getReminderHour() { return reminderHour; }
    public void setReminderHour(int reminderHour) { this.reminderHour = reminderHour; }
    public int getReminderMinCount() { return reminderMinCount; }
    public void setReminderMinCount(int reminderMinCount) { this.reminderMinCount = reminderMinCount; }
    public int getReminderBatchSize() { return reminderBatchSize; }
    public void setReminderBatchSize(int reminderBatchSize) { this.reminderBatchSize = reminderBatchSize; }
    public int getCacheMaxEntries() { return cacheMaxEntries; }
    public void setCacheMaxEntries(int cacheMaxEntries) { this.cacheMaxEntries = cacheMaxEntries; }

    /** The configured zone; an unknown id fails fast at startup rather than per request. */
    public ZoneId zoneId() {
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException ex) {
            throw new IllegalStateException("nexachat.streaks.zone is not a valid time-zone id", ex);
        }
    }
}
