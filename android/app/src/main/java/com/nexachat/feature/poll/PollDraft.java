package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Normalized create payload and the exact client-side validation the server applies. */
public final class PollDraft {

    public static final int MAX_QUESTION_LENGTH = 200;
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 10;
    public static final int MAX_OPTION_LENGTH = 100;
    public static final int MAX_EXPIRY_DAYS = 30;

    private final String question;
    private final List<String> options;
    private final boolean multipleChoice;
    private final boolean anonymous;
    @Nullable private final Instant closesAt;

    public PollDraft(
            @NonNull String question,
            @NonNull List<String> options,
            boolean multipleChoice,
            boolean anonymous,
            @Nullable Instant closesAt) {
        this.question = Objects.requireNonNull(question, "question");
        this.options = List.copyOf(options);
        this.multipleChoice = multipleChoice;
        this.anonymous = anonymous;
        this.closesAt = closesAt;
    }

    @NonNull public String getQuestion() { return question; }
    @NonNull public List<String> getOptions() { return options; }
    public boolean isMultipleChoice() { return multipleChoice; }
    public boolean isAnonymous() { return anonymous; }
    @Nullable public Instant getClosesAt() { return closesAt; }

    @NonNull
    public List<String> normalizedOptions() {
        List<String> normalized = new ArrayList<>(options.size());
        for (String option : options) {
            normalized.add(option == null ? "" : option.trim());
        }
        return normalized;
    }

    @NonNull
    public PollDraft normalized() {
        return new PollDraft(
                question.trim(), normalizedOptions(), multipleChoice, anonymous, closesAt);
    }

    @NonNull
    public PollValidation validate(@NonNull Instant now) {
        String normalizedQuestion = question.trim();
        if (normalizedQuestion.isEmpty()) {
            return PollValidation.QUESTION_REQUIRED;
        }
        if (normalizedQuestion.length() > MAX_QUESTION_LENGTH) {
            return PollValidation.QUESTION_TOO_LONG;
        }
        if (options.size() < MIN_OPTIONS) {
            return PollValidation.TOO_FEW_OPTIONS;
        }
        if (options.size() > MAX_OPTIONS) {
            return PollValidation.TOO_MANY_OPTIONS;
        }
        Set<String> seen = new HashSet<>();
        for (String option : options) {
            String label = option == null ? "" : option.trim();
            if (label.isEmpty()) {
                return PollValidation.OPTION_REQUIRED;
            }
            if (label.length() > MAX_OPTION_LENGTH) {
                return PollValidation.OPTION_TOO_LONG;
            }
            if (!seen.add(label)) {
                return PollValidation.DUPLICATE_OPTION;
            }
        }
        if (closesAt != null) {
            if (!closesAt.isAfter(now)) {
                return PollValidation.EXPIRY_PAST;
            }
            if (closesAt.isAfter(now.plus(Duration.ofDays(MAX_EXPIRY_DAYS)))) {
                return PollValidation.EXPIRY_TOO_FAR;
            }
        }
        return PollValidation.OK;
    }
}
