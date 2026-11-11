package com.nexachat.core.network.dto;

import java.util.Collections;
import java.util.List;

/** Own linked accounts. */
public final class LinkedIdentitiesResponse {
    private List<LinkedIdentityDto> identities;

    public LinkedIdentitiesResponse() {
        this(Collections.emptyList());
    }

    public LinkedIdentitiesResponse(List<LinkedIdentityDto> identities) {
        this.identities = identities;
    }

    public List<LinkedIdentityDto> getIdentities() {
        return identities == null ? Collections.emptyList() : identities;
    }
}
