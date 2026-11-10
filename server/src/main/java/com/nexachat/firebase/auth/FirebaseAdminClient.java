package com.nexachat.firebase.auth;

import java.util.Map;

/** Fakeable Firebase Auth operations used by the bridge and revocation hook. */
public interface FirebaseAdminClient {

    String createCustomToken(String uid, Map<String, Object> additionalClaims);

    void revokeRefreshTokens(String uid);
}
