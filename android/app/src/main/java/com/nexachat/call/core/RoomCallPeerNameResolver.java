package com.nexachat.call.core;

import android.content.Context;
import com.nexachat.R;
import com.nexachat.core.common.AppLocaleManager;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;

/** Resolves an incoming caller from the locally cached direct-conversation row. */
public final class RoomCallPeerNameResolver implements CallPeerNameResolver {
    private final Context appContext;
    private final ConversationDao conversationDao;

    public RoomCallPeerNameResolver(Context context, ConversationDao conversationDao) {
        this.appContext = context.getApplicationContext();
        this.conversationDao = conversationDao;
    }

    @Override
    public String unknownCallerDisplayName() {
        return AppLocaleManager.wrapContext(appContext)
                .getString(R.string.call_unknown_caller);
    }

    @Override
    public void resolve(String conversationId, String peerUserId, Callback callback) {
        if (callback == null) return;
        ConversationEntity row = loadConversation(conversationId);
        if (row == null || !"DIRECT".equals(row.getType())) {
            callback.onResolved(null);
            return;
        }
        String rowPeerUserId = row.getOtherUserId();
        if (hasText(rowPeerUserId) && !rowPeerUserId.equals(peerUserId)) {
            callback.onResolved(null);
            return;
        }
        String displayName = row.getOtherUserName();
        if (!hasText(displayName) || displayName.trim().equalsIgnoreCase(peerUserId)) {
            callback.onResolved(null);
            return;
        }
        callback.onResolved(displayName.trim());
    }

    private ConversationEntity loadConversation(String conversationId) {
        if (!hasText(conversationId)) return null;
        try {
            return BuildersKt.runBlocking(
                    EmptyCoroutineContext.INSTANCE,
                    new Function2<CoroutineScope, Continuation<? super ConversationEntity>, Object>() {
                        @Override
                        public Object invoke(
                                CoroutineScope scope,
                                Continuation<? super ConversationEntity> continuation) {
                            return conversationDao.byId(conversationId, continuation);
                        }
                    });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
