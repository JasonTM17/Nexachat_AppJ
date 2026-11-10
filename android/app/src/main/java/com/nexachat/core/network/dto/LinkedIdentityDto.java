package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** One linked external identity as shown on the profile screen. */
public final class LinkedIdentityDto {
    private String provider;
    private String email;
    private String linkedAt;

    public LinkedIdentityDto() {
        this(null, null, null);
    }

    public LinkedIdentityDto(String provider, String email, String linkedAt) {
        this.provider = provider;
        this.email = email;
        this.linkedAt = linkedAt;
    }

    @NonNull public String getProvider() {
        return provider == null ? "" : provider;
    }

    @Nullable public String getEmail() {
        return email;
    }

    @Nullable public String getLinkedAt() {
        return linkedAt;
    }
}
