package com.nexachat.reminder;

import com.nexachat.conversation.Conversation;
import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code conversation_reminders} (V14).
 *
 * <p>{@code remindAt} is the next occurrence the scheduler will fire.
 * {@code anchorAt} is the immutable first occurrence of a repeating reminder;
 * next occurrences are derived from it so month-end clamping never drifts.
 * One-off reminders are completed by setting {@code firedAt}; repeating rows
 * stay active and move {@code remindAt} forward.</p>
 *
 * <p>{@code claimToken} is the scheduler's lease token. A row can be
 * re-claimed only after its lease expires, which is what makes a crashed
 * scheduler recover without a second live scheduler double-sending.</p>
 */
@Entity
@Table(name = "conversation_reminders")
public class ConversationReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "remind_at", nullable = false)
    private Instant remindAt;

    @Column(name = "anchor_at", nullable = false)
    private Instant anchorAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "repeat_rule", nullable = false, length = 7)
    private ReminderRepeat repeatRule = ReminderRepeat.NONE;

    @Column(name = "fired_at")
    private Instant firedAt;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConversationReminder() {
        // JPA only
    }

    public static ConversationReminder create(Conversation conversation, User createdBy,
                                              String title, Instant remindAt,
                                              ReminderRepeat repeat) {
        ConversationReminder reminder = new ConversationReminder();
        reminder.conversation = conversation;
        reminder.createdBy = createdBy;
        reminder.title = title;
        reminder.remindAt = remindAt;
        reminder.anchorAt = remindAt;
        reminder.repeatRule = repeat != null ? repeat : ReminderRepeat.NONE;
        return reminder;
    }

    @PrePersist
    void onPrePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    public void clearClaim() {
        this.claimedAt = null;
        this.claimToken = null;
    }

    /** Completes a one-off reminder; the row is retained briefly then purged. */
    public void complete(Instant now) {
        this.firedAt = now;
        this.lastFiredAt = now;
        clearClaim();
        this.updatedAt = now;
    }

    /** Advances a repeating reminder to its next future occurrence. */
    public void reschedule(Instant nextOccurrence, Instant now) {
        this.remindAt = nextOccurrence;
        this.lastFiredAt = now;
        clearClaim();
        this.updatedAt = now;
    }

    /**
     * Applies a partial edit. The anchor is reset only when the schedule
     * changes (a new {@code remindAt} or a new repeat rule), so a title-only
     * edit cannot shift a month-end series. A schedule change also invalidates
     * an in-flight scheduler claim: its advance then leaves the edited row
     * active at the new time instead of completing/moving the old occurrence.
     */
    public void edit(String title, Instant remindAt, ReminderRepeat repeat,
                     boolean resetAnchor, Instant now) {
        if (title != null) {
            this.title = title;
        }
        if (remindAt != null) {
            this.remindAt = remindAt;
        }
        if (repeat != null) {
            this.repeatRule = repeat;
        }
        if (resetAnchor) {
            this.anchorAt = this.remindAt;
            clearClaim();
        }
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public String getTitle() {
        return title;
    }

    public Instant getRemindAt() {
        return remindAt;
    }

    public Instant getAnchorAt() {
        return anchorAt;
    }

    public ReminderRepeat getRepeatRule() {
        return repeatRule;
    }

    public Instant getFiredAt() {
        return firedAt;
    }

    public Instant getLastFiredAt() {
        return lastFiredAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
