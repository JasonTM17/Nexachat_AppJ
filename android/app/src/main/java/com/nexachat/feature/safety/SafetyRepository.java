package com.nexachat.feature.safety;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Set;

/** Safety surface: user blocks, abuse reports, and caller-owned message stars. */
public interface SafetyRepository {

    /** One row of the caller's blocked-user list. */
    record BlockedUser(
            String userId,
            String displayName,
            String phone,
            String avatarUrl,
            String blockedAt) { }

    /** One page of the blocked list; {@code nextCursor} is null on the last page. */
    record BlockedPage(List<BlockedUser> items, String nextCursor) { }

    /** One starred message plus the caller's star timestamp. */
    record StarredMessage(
            String starredAt,
            com.nexachat.core.network.dto.MessageDto message) { }

    /** One page of the starred list. */
    record StarredPage(List<StarredMessage> items, String nextCursor) { }

    Completable blockUser(String userId);

    Completable unblockUser(String userId);

    /** Per-peer probe; errors surface so the caller can keep the last known state. */
    Single<Boolean> blockStatus(String userId);

    Single<BlockedPage> listBlocked(int limit, String cursor);

    /**
     * Files an abuse report. {@code targetType} is USER, MESSAGE, or
     * CONVERSATION; {@code messageSnapshot} carries reporter evidence for E2EE
     * messages and is ignored by the server for plaintext ones.
     */
    Completable report(
            String targetType,
            String targetId,
            ReportReason reason,
            String details,
            String messageSnapshot);

    Completable starMessage(String messageId, String conversationId);

    Completable unstarMessage(String messageId, String conversationId);

    Single<StarredPage> listStarred(int limit, String cursor);

    /** Live caller-starred message ids for one conversation (Room-backed). */
    Flowable<Set<String>> starredIds(String conversationId);
}
