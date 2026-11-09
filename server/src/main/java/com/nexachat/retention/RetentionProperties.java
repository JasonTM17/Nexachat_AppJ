package com.nexachat.retention;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated, opt-in controls for bounded retention cleanup. */
@Validated
@ConfigurationProperties(prefix = "nexachat.retention")
public class RetentionProperties {

    @Min(1)
    @Max(500)
    private int batchSize = 100;

    @Min(100)
    @Max(30_000)
    private int statementTimeoutMs = 2_000;

    @Min(5)
    @Max(60)
    private int transactionTimeoutSeconds = 15;

    @Min(60_000)
    @Max(86_400_000)
    private long intervalMs = 3_600_000;

    private boolean enabled;
    private boolean dryRun = true;

    @Valid
    @NotNull
    private Jobs jobs = new Jobs();

    public boolean isJobEnabled(RetentionJobRunner.Job job) {
        return switch (job) {
            case JOIN_REQUESTS -> jobs.joinRequests.isEnabled();
            case INVITE_BANS -> jobs.inviteBans.isEnabled();
            case INVITE_LINKS -> jobs.inviteLinks.isEnabled();
            case IDEMPOTENCY -> jobs.idempotency.isEnabled();
            case REFRESH_TOKENS -> jobs.refreshTokens.isEnabled();
            case DEVICE_TOKENS -> jobs.deviceTokens.isEnabled();
            case REMINDERS -> jobs.reminders.isEnabled();
            case POLLS -> jobs.polls.isEnabled();
            case CALL_RECORDS -> jobs.callRecords.isEnabled();
            case ATTACHMENTS -> jobs.attachments.isEnabled();
            case MESSAGE_TOMBSTONES -> jobs.messageTombstones.isEnabled();
            case REACTION_EVENTS -> jobs.reactionEvents.isEnabled();
            case SUMMARIES -> jobs.summaries.isEnabled();
            case SUMMARY_LIMITS -> jobs.summaryLimits.isEnabled();
            case RAG_CACHE -> jobs.ragCache.isEnabled();
            case RAG_LIMITS -> jobs.ragLimits.isEnabled();
        };
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getStatementTimeoutMs() {
        return statementTimeoutMs;
    }

    public void setStatementTimeoutMs(int statementTimeoutMs) {
        this.statementTimeoutMs = statementTimeoutMs;
    }

    public int getTransactionTimeoutSeconds() {
        return transactionTimeoutSeconds;
    }

    public void setTransactionTimeoutSeconds(int transactionTimeoutSeconds) {
        this.transactionTimeoutSeconds = transactionTimeoutSeconds;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public Jobs getJobs() {
        return jobs;
    }

    public void setJobs(Jobs jobs) {
        this.jobs = jobs;
    }

    public static class Jobs {
        @Valid @NotNull private DaysJob joinRequests = new DaysJob(30);
        @Valid @NotNull private SwitchJob inviteBans = new SwitchJob();
        @Valid @NotNull private InviteLinks inviteLinks = new InviteLinks();
        @Valid @NotNull private Idempotency idempotency = new Idempotency();
        @Valid @NotNull private DaysJob refreshTokens = new DaysJob(30);
        @Valid @NotNull private DeviceTokens deviceTokens = new DeviceTokens();
        @Valid @NotNull private DaysJob reminders = new DaysJob(30);
        @Valid @NotNull private DaysJob polls = new DaysJob(365);
        @Valid @NotNull private DaysJob callRecords = new DaysJob(365);
        @Valid @NotNull private Attachments attachments = new Attachments();
        @Valid @NotNull private DaysJob messageTombstones = new DaysJob(30);
        @Valid @NotNull private SwitchJob reactionEvents = new SwitchJob();
        @Valid @NotNull private SwitchJob summaries = new SwitchJob();
        @Valid @NotNull private Limits summaryLimits = new Limits();
        @Valid @NotNull private SwitchJob ragCache = new SwitchJob();
        @Valid @NotNull private Limits ragLimits = new Limits();

        public DaysJob getJoinRequests() { return joinRequests; }
        public void setJoinRequests(DaysJob value) { joinRequests = value; }
        public SwitchJob getInviteBans() { return inviteBans; }
        public void setInviteBans(SwitchJob value) { inviteBans = value; }
        public InviteLinks getInviteLinks() { return inviteLinks; }
        public void setInviteLinks(InviteLinks value) { inviteLinks = value; }
        public Idempotency getIdempotency() { return idempotency; }
        public void setIdempotency(Idempotency value) { idempotency = value; }
        public DaysJob getRefreshTokens() { return refreshTokens; }
        public void setRefreshTokens(DaysJob value) { refreshTokens = value; }
        public DeviceTokens getDeviceTokens() { return deviceTokens; }
        public void setDeviceTokens(DeviceTokens value) { deviceTokens = value; }
        public DaysJob getReminders() { return reminders; }
        public void setReminders(DaysJob value) { reminders = value; }
        public DaysJob getPolls() { return polls; }
        public void setPolls(DaysJob value) { polls = value; }
        public DaysJob getCallRecords() { return callRecords; }
        public void setCallRecords(DaysJob value) { callRecords = value; }
        public Attachments getAttachments() { return attachments; }
        public void setAttachments(Attachments value) { attachments = value; }
        public DaysJob getMessageTombstones() { return messageTombstones; }
        public void setMessageTombstones(DaysJob value) { messageTombstones = value; }
        public SwitchJob getReactionEvents() { return reactionEvents; }
        public void setReactionEvents(SwitchJob value) { reactionEvents = value; }
        public SwitchJob getSummaries() { return summaries; }
        public void setSummaries(SwitchJob value) { summaries = value; }
        public Limits getSummaryLimits() { return summaryLimits; }
        public void setSummaryLimits(Limits value) { summaryLimits = value; }
        public SwitchJob getRagCache() { return ragCache; }
        public void setRagCache(SwitchJob value) { ragCache = value; }
        public Limits getRagLimits() { return ragLimits; }
        public void setRagLimits(Limits value) { ragLimits = value; }
    }

    public static class SwitchJob {
        private boolean enabled;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class DaysJob extends SwitchJob {
        @Min(1) @Max(3_650)
        private int retentionDays;

        public DaysJob() { this(30); }
        public DaysJob(int retentionDays) { this.retentionDays = retentionDays; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    }

    public static class InviteLinks extends SwitchJob {
        @Min(1) @Max(365)
        private int graceDays = 30;

        public int getGraceDays() { return graceDays; }
        public void setGraceDays(int graceDays) { this.graceDays = graceDays; }
    }

    public static class Idempotency extends SwitchJob {
        @Min(1) @Max(8_760)
        private int ttlHours = 24;

        public int getTtlHours() { return ttlHours; }
        public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
    }

    public static class DeviceTokens extends SwitchJob {
        @Min(1) @Max(3_650)
        private int staleDays = 180;

        public int getStaleDays() { return staleDays; }
        public void setStaleDays(int staleDays) { this.staleDays = staleDays; }
    }

    public static class Attachments extends SwitchJob {
        @Min(1) @Max(8_760)
        private int unboundHours = 24;
        @Min(1) @Max(3_650)
        private int deletedMessageDays = 30;

        public int getUnboundHours() { return unboundHours; }
        public void setUnboundHours(int unboundHours) { this.unboundHours = unboundHours; }
        public int getDeletedMessageDays() { return deletedMessageDays; }
        public void setDeletedMessageDays(int deletedMessageDays) {
            this.deletedMessageDays = deletedMessageDays;
        }
    }

    public static class Limits extends SwitchJob {
        @Min(1) @Max(30)
        private int minuteRetentionDays = 1;
        @Min(1) @Max(365)
        private int dayRetentionDays = 2;

        public int getMinuteRetentionDays() { return minuteRetentionDays; }
        public void setMinuteRetentionDays(int value) { minuteRetentionDays = value; }
        public int getDayRetentionDays() { return dayRetentionDays; }
        public void setDayRetentionDays(int value) { dayRetentionDays = value; }
    }
}
