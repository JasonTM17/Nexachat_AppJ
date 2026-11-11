package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;

/** S1 bridge bootstrap response. The custom token is never logged or persisted. */
public final class FirebaseBridgeResponse {
    @Nullable private String customToken;
    @Nullable private String databaseUrl;
    @Nullable private String bridgeId;
    @Nullable private String leaseUntil;
    @Nullable private String transport;

    public FirebaseBridgeResponse() { }

    @Nullable public String getCustomToken() { return customToken; }
    @Nullable public String getDatabaseUrl() { return databaseUrl; }
    @Nullable public String getBridgeId() { return bridgeId; }
    @Nullable public String getLeaseUntil() { return leaseUntil; }
    @Nullable public String getTransport() { return transport; }
}
