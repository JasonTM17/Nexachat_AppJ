package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;

/** S1 lease renewal response. */
public final class FirebaseLeaseResponse {
    @Nullable private String leaseUntil;
    @Nullable private String transport;

    public FirebaseLeaseResponse() { }

    @Nullable public String getLeaseUntil() { return leaseUntil; }
    @Nullable public String getTransport() { return transport; }
}
