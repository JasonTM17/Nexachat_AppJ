package com.nexachat.core.ui;

import com.nexachat.core.storage.RecallWindowConfig;

/** Pure action policy for the client-side message recall affordance. */
public final class RecallWindowRules {
    public enum DeleteAction { NONE, RECALL, DELETE_FOR_ME, ADMIN_DELETE }

    private RecallWindowRules() {}

    public static DeleteAction deleteAction(
            boolean sent,
            boolean deleted,
            boolean mine,
            boolean groupAdmin,
            boolean groupAdminMayRecallAfterWindow,
            long createdAtEpochMs,
            long nowEpochMs,
            long recallWindowSeconds) {
        if (!sent || deleted) return DeleteAction.NONE;

        long windowSeconds = RecallWindowConfig.isValidWindow(recallWindowSeconds)
                ? recallWindowSeconds
                : RecallWindowConfig.DEFAULT_RECALL_WINDOW_SECONDS;
        long windowMs = windowSeconds * 1_000L;
        if (mine && nowEpochMs - createdAtEpochMs < windowMs) {
            return DeleteAction.RECALL;
        }
        if (groupAdmin && groupAdminMayRecallAfterWindow) {
            return DeleteAction.ADMIN_DELETE;
        }
        return mine ? DeleteAction.DELETE_FOR_ME : DeleteAction.NONE;
    }
}
