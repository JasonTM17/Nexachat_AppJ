package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * {@code PUT /api/v1/users/me/phone} body. {@code password} carries the
 * current account password the server requires to replace an already linked
 * phone; a null value is omitted from the JSON (first link, or accounts
 * without a password).
 */
public final class LinkPhoneRequest {
    @NonNull private final String phone;
    @Nullable private final String password;

    public LinkPhoneRequest(@NonNull String phone) {
        this(phone, null);
    }

    public LinkPhoneRequest(@NonNull String phone, @Nullable String password) {
        this.phone = Objects.requireNonNull(phone, "phone");
        this.password = password;
    }

    @NonNull
    public String getPhone() {
        return phone;
    }

    @Nullable
    public String getPassword() {
        return password;
    }
}
