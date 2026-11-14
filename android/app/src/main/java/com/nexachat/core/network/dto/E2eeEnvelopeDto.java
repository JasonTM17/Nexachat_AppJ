package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/** One per-device wrapped content key inside {@code e2eeEnvelopes}. */
public final class E2eeEnvelopeDto {
    @Nullable private final String deviceId;
    @Nullable private final String wrappedKey;

    public E2eeEnvelopeDto() {
        this(null, null);
    }

    public E2eeEnvelopeDto(@NonNull String deviceId, @NonNull String wrappedKey) {
        this.deviceId = deviceId;
        this.wrappedKey = wrappedKey;
    }

    @Nullable public String getDeviceId() {
        return deviceId;
    }

    @Nullable public String getWrappedKey() {
        return wrappedKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof E2eeEnvelopeDto that)) {
            return false;
        }
        return Objects.equals(deviceId, that.deviceId)
                && Objects.equals(wrappedKey, that.wrappedKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, wrappedKey);
    }
}
