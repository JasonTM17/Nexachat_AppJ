package com.nexachat.feature.invite;

import androidx.annotation.Nullable;

/** Immutable rendering state for invite preview, join, and approval status. */
public final class InviteJoinUiState {
    public enum Status {
        LOADING,
        PREVIEW,
        ALREADY_MEMBER,
        JOINED,
        PENDING,
        APPROVED,
        REJECTED,
        ERROR
    }

    private final Status status;
    private final boolean busy;
    @Nullable private final InvitePreviewResponse preview;
    @Nullable private final String conversationId;
    @Nullable private final InviteFailure failure;

    private InviteJoinUiState(
            Status status,
            boolean busy,
            @Nullable InvitePreviewResponse preview,
            @Nullable String conversationId,
            @Nullable InviteFailure failure) {
        this.status = status;
        this.busy = busy;
        this.preview = preview;
        this.conversationId = conversationId;
        this.failure = failure;
    }

    public static InviteJoinUiState loading() {
        return new InviteJoinUiState(Status.LOADING, true, null, null, null);
    }

    public Status getStatus() { return status; }
    public boolean isBusy() { return busy; }
    @Nullable public InvitePreviewResponse getPreview() { return preview; }
    @Nullable public String getConversationId() { return conversationId; }
    @Nullable public InviteFailure getFailure() { return failure; }

    public InviteJoinUiState withPreview(InvitePreviewResponse value) {
        return new InviteJoinUiState(
                value.isMember ? Status.ALREADY_MEMBER : Status.PREVIEW,
                false,
                value,
                value.conversationId,
                null);
    }

    public InviteJoinUiState withBusy() {
        return new InviteJoinUiState(status, true, preview, conversationId, null);
    }

    public InviteJoinUiState withStatus(Status value, @Nullable String nextConversationId) {
        return new InviteJoinUiState(value, false, preview, nextConversationId, null);
    }

    public InviteJoinUiState withFailure(InviteFailure value) {
        Status nextStatus = status == Status.PENDING || status == Status.APPROVED
                ? status : Status.ERROR;
        return new InviteJoinUiState(nextStatus, false, preview, conversationId, value);
    }
}
