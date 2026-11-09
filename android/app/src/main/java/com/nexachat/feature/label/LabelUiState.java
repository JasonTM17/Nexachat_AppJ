package com.nexachat.feature.label;

import androidx.annotation.Nullable;
import java.util.List;
import java.util.Set;

/** Immutable UI snapshot for label management, assignment, and inbox filtering. */
public final class LabelUiState {
    public enum Error {
        NONE,
        VALIDATION,
        CONFLICT,
        RATE_LIMITED,
        OFFLINE,
        LABEL_LIMIT,
        ASSIGNMENT_LIMIT,
        GENERIC
    }

    private final List<ConversationLabel> labels;
    private final boolean labelsLoading;
    private final boolean labelsLoaded;
    @Nullable private final String selectedFilterId;
    private final Set<String> filteredConversationIds;
    private final boolean filterLoading;
    private final Error filterError;
    @Nullable private final String assignmentConversationId;
    private final List<String> assignmentIds;
    private final boolean assignmentLoading;
    private final boolean assignmentSaving;
    private final boolean labelsMutating;
    private final Error error;
    private final long errorVersion;

    public LabelUiState(
            List<ConversationLabel> labels,
            boolean labelsLoading,
            boolean labelsLoaded,
            @Nullable String selectedFilterId,
            Set<String> filteredConversationIds,
            boolean filterLoading,
            Error filterError,
            @Nullable String assignmentConversationId,
            List<String> assignmentIds,
            boolean assignmentLoading,
            boolean assignmentSaving,
            boolean labelsMutating,
            Error error,
            long errorVersion) {
        this.labels = List.copyOf(labels);
        this.labelsLoading = labelsLoading;
        this.labelsLoaded = labelsLoaded;
        this.selectedFilterId = selectedFilterId;
        this.filteredConversationIds = Set.copyOf(filteredConversationIds);
        this.filterLoading = filterLoading;
        this.filterError = filterError;
        this.assignmentConversationId = assignmentConversationId;
        this.assignmentIds = List.copyOf(assignmentIds);
        this.assignmentLoading = assignmentLoading;
        this.assignmentSaving = assignmentSaving;
        this.labelsMutating = labelsMutating;
        this.error = error;
        this.errorVersion = errorVersion;
    }

    public List<ConversationLabel> getLabels() { return labels; }
    public boolean isLabelsLoading() { return labelsLoading; }
    public boolean isLabelsLoaded() { return labelsLoaded; }
    @Nullable public String getSelectedFilterId() { return selectedFilterId; }
    public Set<String> getFilteredConversationIds() { return filteredConversationIds; }
    public boolean isFilterLoading() { return filterLoading; }
    public Error getFilterError() { return filterError; }
    @Nullable public String getAssignmentConversationId() { return assignmentConversationId; }
    public List<String> getAssignmentIds() { return assignmentIds; }
    public boolean isAssignmentLoading() { return assignmentLoading; }
    public boolean isAssignmentSaving() { return assignmentSaving; }
    public boolean isLabelsMutating() { return labelsMutating; }
    public Error getError() { return error; }
    public long getErrorVersion() { return errorVersion; }
}
