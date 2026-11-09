package com.nexachat.feature.poll;

import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Reflective Moshi bean for one poll response. */
public final class PollDto {
    @Nullable private final String id;
    @Nullable private final String conversationId;
    @Nullable private final String createdBy;
    @Nullable private final String question;
    @Nullable private final Boolean multipleChoice;
    @Nullable private final Boolean anonymous;
    @Nullable private final String status;
    @Nullable private final String closesAt;
    @Nullable private final String closedAt;
    @Nullable private final String closedBy;
    @Nullable private final String createdAt;
    @Nullable private final Long version;
    @Nullable private final Integer voterCount;
    @Nullable private final List<PollOptionDto> options;
    @Nullable private final List<String> myOptionIds;

    public PollDto() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public PollDto(
            @Nullable String id,
            @Nullable String conversationId,
            @Nullable String createdBy,
            @Nullable String question,
            @Nullable Boolean multipleChoice,
            @Nullable Boolean anonymous,
            @Nullable String status,
            @Nullable String closesAt,
            @Nullable String closedAt,
            @Nullable String closedBy,
            @Nullable String createdAt,
            @Nullable Long version,
            @Nullable Integer voterCount,
            @Nullable List<PollOptionDto> options,
            @Nullable List<String> myOptionIds) {
        this.id = id;
        this.conversationId = conversationId;
        this.createdBy = createdBy;
        this.question = question;
        this.multipleChoice = multipleChoice;
        this.anonymous = anonymous;
        this.status = status;
        this.closesAt = closesAt;
        this.closedAt = closedAt;
        this.closedBy = closedBy;
        this.createdAt = createdAt;
        this.version = version;
        this.voterCount = voterCount;
        this.options = options;
        this.myOptionIds = myOptionIds;
    }

    @Nullable public String getId() { return id; }
    @Nullable public String getConversationId() { return conversationId; }
    @Nullable public String getCreatedBy() { return createdBy; }
    @Nullable public String getQuestion() { return question; }
    public boolean isMultipleChoice() { return Boolean.TRUE.equals(multipleChoice); }
    public boolean isAnonymous() { return Boolean.TRUE.equals(anonymous); }
    @Nullable public String getStatus() { return status; }
    @Nullable public String getClosesAt() { return closesAt; }
    @Nullable public String getClosedAt() { return closedAt; }
    @Nullable public String getClosedBy() { return closedBy; }
    @Nullable public String getCreatedAt() { return createdAt; }
    public long getVersion() { return version == null ? 0L : version; }
    public int getVoterCount() { return voterCount == null ? 0 : voterCount; }
    @Nullable public List<PollOptionDto> getOptions() { return options; }
    @Nullable public List<String> getMyOptionIds() { return myOptionIds; }

    @Nullable
    public Poll toDomain() {
        if (isBlank(id) || isBlank(conversationId) || isBlank(createdBy)
                || isBlank(question)) {
            return null;
        }
        Instant created = PollTime.parse(createdAt);
        if (created == null) {
            return null;
        }
        List<PollOption> domainOptions = new ArrayList<>();
        if (options != null) {
            for (PollOptionDto dto : options) {
                PollOption option = dto == null ? null : dto.toDomain(isAnonymous());
                if (option != null) {
                    domainOptions.add(option);
                }
            }
        }
        List<String> mine = new ArrayList<>();
        if (myOptionIds != null) {
            for (String optionId : myOptionIds) {
                if (!isBlank(optionId)) {
                    mine.add(optionId);
                }
            }
        }
        return new Poll(
                id,
                conversationId,
                createdBy,
                question,
                isMultipleChoice(),
                isAnonymous(),
                PollStatus.fromWire(status),
                PollTime.parse(closesAt),
                PollTime.parse(closedAt),
                closedBy,
                created,
                getVersion(),
                getVoterCount(),
                domainOptions,
                mine);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
