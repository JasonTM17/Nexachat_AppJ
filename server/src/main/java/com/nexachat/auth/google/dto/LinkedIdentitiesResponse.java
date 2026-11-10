package com.nexachat.auth.google.dto;

import java.util.List;

/** Own linked accounts; never exposes the provider subject. */
public record LinkedIdentitiesResponse(List<LinkedIdentityResponse> identities) {

    public LinkedIdentitiesResponse {
        identities = List.copyOf(identities);
    }
}
