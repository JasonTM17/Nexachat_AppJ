package com.nexachat.feature.invite;

import androidx.annotation.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class FakeInviteRepository implements InviteRepository {
    Single<Optional<InviteLinkResponse>> activeLink = Single.just(Optional.empty());
    Single<InviteLinkResponse> createdLink = Single.just(link("token", false));
    Single<InviteLinkResponse> updatedLink = Single.just(link(null, true));
    Single<List<JoinRequestResponse>> pendingRequests;
    Completable revokeResult = Completable.complete();
    Single<InvitePreviewResponse> preview = Single.just(preview(false, false));
    Single<JoinResponse> join = Single.just(joinResult("JOINED", "group-1"));
    Single<Optional<JoinRequestResponse>> myRequest = Single.just(Optional.empty());
    List<JoinRequestResponse> requests = new ArrayList<>();
    String lastConversationId;
    String lastToken;
    String lastRequestId;
    InviteLinkRequest lastCreateRequest;
    Boolean lastApprovalValue;
    int previewCalls;
    int joinCalls;
    int statusCalls;
    int approveCalls;
    int rejectCalls;
    int revokeCalls;

    @NonNull @Override
    public Single<Optional<InviteLinkResponse>> getActiveLink(@NonNull String conversationId) {
        lastConversationId = conversationId;
        return activeLink;
    }

    @NonNull @Override
    public Single<InviteLinkResponse> createOrRotateLink(
            @NonNull String conversationId, @NonNull InviteLinkRequest request) {
        lastConversationId = conversationId;
        lastCreateRequest = request;
        return createdLink;
    }

    @NonNull @Override
    public Single<InviteLinkResponse> updateApproval(
            @NonNull String conversationId, boolean requiresApproval) {
        lastConversationId = conversationId;
        lastApprovalValue = requiresApproval;
        return updatedLink;
    }

    @NonNull @Override
    public Completable revokeLink(@NonNull String conversationId) {
        lastConversationId = conversationId;
        revokeCalls++;
        return revokeResult;
    }

    @NonNull @Override
    public Single<List<JoinRequestResponse>> listPendingRequests(@NonNull String conversationId) {
        lastConversationId = conversationId;
        return pendingRequests == null
                ? Single.fromCallable(() -> List.copyOf(requests))
                : pendingRequests;
    }

    @NonNull @Override
    public Completable approveRequest(@NonNull String conversationId, @NonNull String requestId) {
        lastConversationId = conversationId;
        lastRequestId = requestId;
        approveCalls++;
        requests.removeIf(item -> requestId.equals(item.id));
        return Completable.complete();
    }

    @NonNull @Override
    public Completable rejectRequest(@NonNull String conversationId, @NonNull String requestId) {
        lastConversationId = conversationId;
        lastRequestId = requestId;
        rejectCalls++;
        requests.removeIf(item -> requestId.equals(item.id));
        return Completable.complete();
    }

    @NonNull @Override
    public Single<InvitePreviewResponse> loadPreview(@NonNull String token) {
        lastToken = token;
        previewCalls++;
        return preview;
    }

    @NonNull @Override
    public Single<JoinResponse> join(@NonNull String token) {
        lastToken = token;
        joinCalls++;
        return join;
    }

    @NonNull @Override
    public Single<Optional<JoinRequestResponse>> getMyJoinRequest(@NonNull String conversationId) {
        lastConversationId = conversationId;
        statusCalls++;
        return myRequest;
    }

    static InviteLinkResponse link(String token, boolean approval) {
        InviteLinkResponse value = new InviteLinkResponse();
        value.id = "link-1";
        value.conversationId = "group-1";
        value.token = token;
        value.requiresApproval = approval;
        value.useCount = 2;
        return value;
    }

    static InvitePreviewResponse preview(boolean member, boolean approval) {
        InvitePreviewResponse value = new InvitePreviewResponse();
        value.conversationId = "group-1";
        value.title = "Travel group";
        value.avatarUrl = null;
        value.memberCount = 8;
        value.requiresApproval = approval;
        value.isMember = member;
        return value;
    }

    static JoinResponse joinResult(String status, String conversationId) {
        JoinResponse value = new JoinResponse();
        value.status = status;
        value.conversationId = conversationId;
        value.requestId = "request-1";
        return value;
    }

    static JoinRequestResponse request(String status) {
        JoinRequestResponse value = new JoinRequestResponse();
        value.id = "request-1";
        value.conversationId = "group-1";
        value.status = status;
        value.createdAt = "2026-11-09T00:00:00Z";
        value.user = new JoinRequestResponse.UserSummary();
        value.user.id = "user-1";
        value.user.displayName = "Member";
        return value;
    }
}
