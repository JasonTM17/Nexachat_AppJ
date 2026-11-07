package com.nexachat.data;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.Result;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.network.GroupApi;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.storage.TokenStore;
import com.squareup.moshi.Moshi;
import dagger.hilt.android.qualifiers.ApplicationContext;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.Flow;

/**
 * Inbox and group boundary. Room is the render source; REST refresh is the
 * authoritative write-through. The coroutine body lives in
 * {@link ConversationRepositoryCore}.
 */
@Singleton
public final class ConversationRepository implements ConversationRepositoryApi {

    private final ConversationRepositoryCore core;

    @Inject
    public ConversationRepository(
            @NonNull MessageApi messageApi,
            @NonNull GroupApi groupApi,
            @NonNull ConversationDao conversationDao,
            @NonNull PresenceRepository presenceRepository,
            @NonNull RealtimeSession session,
            @NonNull TokenStore tokenStore,
            @NonNull Moshi moshi,
            @NonNull CoroutineScope scope,
            @ApplicationContext @NonNull Context context) {
        this.core = new ConversationRepositoryCore(
                messageApi,
                groupApi,
                conversationDao,
                presenceRepository,
                session,
                tokenStore,
                moshi,
                scope,
                context);
    }

    @NonNull
    @Override
    public Flow<List<ConversationSummary>> inboxFlow() {
        return core.inboxFlow();
    }

    @NonNull
    @Override
    public Flow<ConversationSummary> conversationFlow(@NonNull String conversationId) {
        return core.conversationFlow(conversationId);
    }

    @NonNull
    @Override
    public Flow<Unit> groupUpdates(@NonNull String conversationId) {
        return core.groupUpdates(conversationId);
    }

    @Override
    public Object refreshInbox(@NonNull Continuation<? super Result<Unit>> completion) {
        return core.refreshInbox(completion);
    }

    @Nullable
    @Override
    public Object myUserId(@NonNull Continuation<? super String> completion) {
        return core.myUserId(completion);
    }

    @Override
    public Object createGroup(
            @NonNull String title,
            @NonNull List<String> memberIds,
            @NonNull Continuation<? super Result<String>> completion) {
        return core.createGroup(title, memberIds, completion);
    }

    @Override
    public Object groupDetail(
            @NonNull String conversationId,
            @NonNull Continuation<? super Result<GroupDetail>> completion) {
        return core.groupDetail(conversationId, completion);
    }

    @Override
    public Object addGroupMembers(
            @NonNull String conversationId,
            @NonNull List<String> userIds,
            @NonNull Continuation<? super Result<Unit>> completion) {
        return core.addGroupMembers(conversationId, userIds, completion);
    }

    @Override
    public Object removeGroupMember(
            @NonNull String conversationId,
            @NonNull String userId,
            @NonNull Continuation<? super Result<Unit>> completion) {
        return core.removeGroupMember(conversationId, userId, completion);
    }

    @Override
    public Object renameGroup(
            @NonNull String conversationId,
            @NonNull String title,
            @NonNull Continuation<? super Result<Unit>> completion) {
        return core.renameGroup(conversationId, title, completion);
    }

    @Override
    public Object setGroupAvatar(
            @NonNull String conversationId,
            @NonNull Uri uri,
            @NonNull Continuation<? super Result<Unit>> completion) {
        return core.setGroupAvatar(conversationId, uri, completion);
    }

    @Override
    public Object leaveGroup(
            @NonNull String conversationId,
            @NonNull Continuation<? super Result<Unit>> completion) {
        return core.leaveGroup(conversationId, completion);
    }
}
