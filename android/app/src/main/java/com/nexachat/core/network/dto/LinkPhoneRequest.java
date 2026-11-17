package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.Objects;

/** {@code PUT /api/v1/users/me/phone} body. */
public final class LinkPhoneRequest {
    @NonNull private final String phone;

    public LinkPhoneRequest(@NonNull String phone) {
        this.phone = Objects.requireNonNull(phone, "phone");
    }

    @NonNull
    public String getPhone() {
        return phone;
    }
}
