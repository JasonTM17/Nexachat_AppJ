package com.nexachat.call.core;

import android.content.Context;
import com.nexachat.R;
import com.nexachat.core.common.AppLocaleManager;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;

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
            return conversationDao.byId(conversationId).blockingGet();
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
