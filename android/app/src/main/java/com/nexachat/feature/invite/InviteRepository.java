package com.nexachat.feature.invite;

import androidx.annotation.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;

/** Cancellable boundary for invite-link operations. */
public interface InviteRepository {
    @NonNull Single<Optional<InviteLinkResponse>> getActiveLink(@NonNull String conversationId);
    @NonNull Single<InviteLinkResponse> createOrRotateLink(
            @NonNull String conversationId, @NonNull InviteLinkRequest request);
    @NonNull Single<InviteLinkResponse> updateApproval(
            @NonNull String conversationId, boolean requiresApproval);
    @NonNull Completable revokeLink(@NonNull String conversationId);
    @NonNull Single<List<JoinRequestResponse>> listPendingRequests(@NonNull String conversationId);
    @NonNull Completable approveRequest(@NonNull String conversationId, @NonNull String requestId);
    @NonNull Completable rejectRequest(@NonNull String conversationId, @NonNull String requestId);
    @NonNull Single<InvitePreviewResponse> loadPreview(@NonNull String token);
    @NonNull Single<JoinResponse> join(@NonNull String token);
    @NonNull Single<Optional<JoinRequestResponse>> getMyJoinRequest(@NonNull String conversationId);
}
