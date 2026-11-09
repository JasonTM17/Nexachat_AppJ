package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One group poll with results and the caller's current selection. */
public final class Poll {

    private final String id;
    private final String conversationId;
    private final String createdBy;
    private final String question;
    private final boolean multipleChoice;
    private final boolean anonymous;
    private final PollStatus status;
    @Nullable private final Instant closesAt;
    @Nullable private final Instant closedAt;
    @Nullable private final String closedBy;
    private final Instant createdAt;
    private final long version;
    private final int voterCount;
    private final List<PollOption> options;
    private final List<String> myOptionIds;

    public Poll(
            @NonNull String id,
            @NonNull String conversationId,
            @NonNull String createdBy,
            @NonNull String question,
            boolean multipleChoice,
            boolean anonymous,
            @NonNull PollStatus status,
            @Nullable Instant closesAt,
            @Nullable Instant closedAt,
            @Nullable String closedBy,
            @NonNull Instant createdAt,
            long version,
            int voterCount,
            @NonNull List<PollOption> options,
            @NonNull List<String> myOptionIds) {
        this.id = Objects.requireNonNull(id, "id");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.question = Objects.requireNonNull(question, "question");
        this.multipleChoice = multipleChoice;
        this.anonymous = anonymous;
        this.status = Objects.requireNonNull(status, "status");
        this.closesAt = closesAt;
        this.closedAt = closedAt;
        this.closedBy = closedBy;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
        this.voterCount = Math.max(0, voterCount);
        List<PollOption> copiedOptions = new ArrayList<>(options.size());
        for (PollOption option : options) {
            Objects.requireNonNull(option, "option");
            copiedOptions.add(option);
        }
        this.options = Collections.unmodifiableList(copiedOptions);
        this.myOptionIds = Collections.unmodifiableList(new ArrayList<>(myOptionIds));
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getConversationId() { return conversationId; }
    @NonNull public String getCreatedBy() { return createdBy; }
    @NonNull public String getQuestion() { return question; }
    public boolean isMultipleChoice() { return multipleChoice; }
    public boolean isAnonymous() { return anonymous; }
    @NonNull public PollStatus getStatus() { return status; }
    @Nullable public Instant getClosesAt() { return closesAt; }
    @Nullable public Instant getClosedAt() { return closedAt; }
    @Nullable public String getClosedBy() { return closedBy; }
    @NonNull public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
    public int getVoterCount() { return voterCount; }
    @NonNull public List<PollOption> getOptions() { return options; }
    @NonNull public List<String> getMyOptionIds() { return myOptionIds; }

    /** Closed means explicitly closed or at/past its inclusive expiry boundary. */
    public boolean isClosed(@NonNull Instant now) {
        return status == PollStatus.CLOSED
                || (closesAt != null && !closesAt.isAfter(now));
    }

    /** Local close marker used when the server rejects a vote as already closed. */
    @NonNull
    public Poll asClosed(@NonNull Instant now) {
        if (status == PollStatus.CLOSED) {
            return this;
        }
        return new Poll(id, conversationId, createdBy, question, multipleChoice, anonymous,
                PollStatus.CLOSED, closesAt, closedAt == null ? now : closedAt, closedBy,
                createdAt, version, voterCount, options, myOptionIds);
    }

    /** Defense in depth: an anonymous poll must never retain voter identities in the UI. */
    @NonNull
    public Poll sanitized() {
        if (!anonymous) {
            return this;
        }
        boolean needsCleanup = false;
        for (PollOption option : options) {
            if (!option.getVoterIds().isEmpty()) {
                needsCleanup = true;
                break;
            }
        }
        if (!needsCleanup) {
            return this;
        }
        List<PollOption> cleaned = new ArrayList<>(options.size());
        for (PollOption option : options) {
            cleaned.add(new PollOption(
                    option.getId(), option.getPosition(), option.getLabel(),
                    option.getVoteCount(), List.of()));
        }
        return new Poll(id, conversationId, createdBy, question, multipleChoice, anonymous,
                status, closesAt, closedAt, closedBy, createdAt, version, voterCount,
                cleaned, myOptionIds);
    }

    @NonNull
    public Poll withSelection(@NonNull List<String> selection, @Nullable String myUserId) {
        java.util.Set<String> previous = new java.util.HashSet<>(myOptionIds);
        java.util.Set<String> next = new java.util.HashSet<>(selection);
        int nextVoterCount = voterCount;
        if (previous.isEmpty() && !next.isEmpty()) {
            nextVoterCount++;
        } else if (!previous.isEmpty() && next.isEmpty()) {
            nextVoterCount = Math.max(0, nextVoterCount - 1);
        }
        List<PollOption> nextOptions = new ArrayList<>(options.size());
        for (PollOption option : options) {
            int delta = (next.contains(option.getId()) ? 1 : 0)
                    - (previous.contains(option.getId()) ? 1 : 0);
            int count = Math.max(0, option.getVoteCount() + delta);
            List<String> voters = new ArrayList<>(option.getVoterIds());
            if (!anonymous && myUserId != null) {
                voters.remove(myUserId);
                if (next.contains(option.getId())) {
                    voters.add(myUserId);
                }
            }
            nextOptions.add(new PollOption(
                    option.getId(), option.getPosition(), option.getLabel(), count, voters));
        }
        return new Poll(id, conversationId, createdBy, question, multipleChoice, anonymous,
                status, closesAt, closedAt, closedBy, createdAt, version, nextVoterCount,
                nextOptions, new ArrayList<>(selection));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Poll that)) {
            return false;
        }
        return multipleChoice == that.multipleChoice
                && anonymous == that.anonymous
                && version == that.version
                && voterCount == that.voterCount
                && id.equals(that.id)
                && conversationId.equals(that.conversationId)
                && createdBy.equals(that.createdBy)
                && question.equals(that.question)
                && status == that.status
                && Objects.equals(closesAt, that.closesAt)
                && Objects.equals(closedAt, that.closedAt)
                && Objects.equals(closedBy, that.closedBy)
                && createdAt.equals(that.createdAt)
                && options.equals(that.options)
                && myOptionIds.equals(that.myOptionIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, conversationId, createdBy, question, multipleChoice, anonymous,
                status, closesAt, closedAt, closedBy, createdAt, version, voterCount, options,
                myOptionIds);
    }
}
