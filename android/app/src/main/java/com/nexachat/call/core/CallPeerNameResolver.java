package com.nexachat.call.core;

/** Resolves an incoming caller's local display name from work dispatched off the main thread. */
public interface CallPeerNameResolver {
    interface Callback {
        void onResolved(String displayName);
    }

    /** Returns the locale-aware fallback label for an unresolved caller. */
    String unknownCallerDisplayName();

    /** Looks up the peer from the local row for a direct conversation. */
    void resolve(String conversationId, String peerUserId, Callback callback);
}
