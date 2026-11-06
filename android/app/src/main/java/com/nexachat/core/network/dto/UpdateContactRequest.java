package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class UpdateContactRequest {
    @Nullable private final String aliasName;

    public UpdateContactRequest() {
        this(null);
    }

    public UpdateContactRequest(@Nullable String aliasName) {
        this.aliasName = aliasName;
    }

    @Nullable public String getAliasName() {
        return aliasName;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UpdateContactRequest that)) {
            return false;
        }
        return Objects.equals(aliasName, that.aliasName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(aliasName);
    }
}
