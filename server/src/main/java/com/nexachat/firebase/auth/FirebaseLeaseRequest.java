package com.nexachat.firebase.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record FirebaseLeaseRequest(
        @NotNull UUID bridgeId,
        @NotNull @Size(max = 30) List<@NotNull UUID> conversationIds) {
}
