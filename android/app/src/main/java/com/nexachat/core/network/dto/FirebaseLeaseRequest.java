package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Current, bounded observer scope sent when renewing a Firebase bridge lease. */
public final class FirebaseLeaseRequest {
    @NonNull private final String bridgeId;
    @NonNull private final List<String> conversationIds;

    public FirebaseLeaseRequest(String bridgeId, List<String> conversationIds) {
        this.bridgeId = Objects.requireNonNull(bridgeId, "bridgeId");
        this.conversationIds = List.copyOf(Objects.requireNonNull(
                conversationIds, "conversationIds"));
    }

    @NonNull public String getBridgeId() { return bridgeId; }
    @NonNull public List<String> getConversationIds() { return conversationIds; }
}
