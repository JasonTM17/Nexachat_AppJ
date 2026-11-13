package com.nexachat.feature.invite;

import androidx.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/** Immutable presentation state for the administrator invite screen. */
public final class InviteManageUiState {
    private final boolean loading;
    private final boolean busy;
    private final boolean loaded;
    @Nullable private final InviteLinkResponse link;
    @Nullable private final String shareUrl;
    private final List<JoinRequestResponse> requests;
    @Nullable private final InviteFailure failure;

    private InviteManageUiState(
            boolean loading,
            boolean busy,
            boolean loaded,
            @Nullable InviteLinkResponse link,
            @Nullable String shareUrl,
            List<JoinRequestResponse> requests,
            @Nullable InviteFailure failure) {
        this.loading = loading;
        this.busy = busy;
        this.loaded = loaded;
        this.link = link;
        this.shareUrl = shareUrl;
        this.requests = List.copyOf(requests);
        this.failure = failure;
    }

    public static InviteManageUiState initial() {
        return new InviteManageUiState(true, false, false, null, null, List.of(), null);
    }

    public boolean isLoading() { return loading; }
    public boolean isBusy() { return busy; }
    public boolean isLoaded() { return loaded; }
    @Nullable public InviteLinkResponse getLink() { return link; }
    @Nullable public String getShareUrl() { return shareUrl; }
    public List<JoinRequestResponse> getRequests() { return requests; }
    @Nullable public InviteFailure getFailure() { return failure; }

    public boolean canShare() {
        return shareUrl != null;
    }

    public InviteManageUiState withLoading(boolean value) {
        return copy(value, busy, loaded, link, shareUrl, requests, failure);
    }

    public InviteManageUiState withBusy(boolean value) {
        return copy(loading, value, loaded, link, shareUrl, requests, value ? null : failure);
    }

    public InviteManageUiState withLoaded(
            Optional<InviteLinkResponse> loadedLink,
            List<JoinRequestResponse> loadedRequests) {
        return copy(false, false, true, loadedLink.orElse(null), null, loadedRequests, null);
    }

    public InviteManageUiState withLink(
            @Nullable InviteLinkResponse value, @Nullable String nextShareUrl) {
        return copy(false, false, loaded, value, nextShareUrl, requests, null);
    }

    public InviteManageUiState withRequests(List<JoinRequestResponse> value) {
        return copy(false, false, loaded, link, shareUrl, value, null);
    }

    public InviteManageUiState withFailure(InviteFailure value) {
        return copy(false, false, loaded, link, shareUrl, requests, value);
    }

    public InviteManageUiState clearFailure() {
        return copy(loading, busy, loaded, link, shareUrl, requests, null);
    }

    private static InviteManageUiState copy(
            boolean loading,
            boolean busy,
            boolean loaded,
            @Nullable InviteLinkResponse link,
            @Nullable String shareUrl,
            List<JoinRequestResponse> requests,
            @Nullable InviteFailure failure) {
        return new InviteManageUiState(loading, busy, loaded, link, shareUrl, requests, failure);
    }
}
