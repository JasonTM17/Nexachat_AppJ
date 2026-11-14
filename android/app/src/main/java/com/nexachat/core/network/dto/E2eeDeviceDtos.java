package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

/** DTOs for the E2EE device-registry and conversation-toggle endpoints. */
public final class E2eeDeviceDtos {

    private E2eeDeviceDtos() {
    }

    /** {@code PUT /e2ee/devices/{deviceId}} body. */
    public static final class RegisterDeviceRequest {
        @NonNull private final String publicKey;

        public RegisterDeviceRequest(@NonNull String publicKey) {
            this.publicKey = publicKey;
        }

        @NonNull public String getPublicKey() {
            return publicKey;
        }
    }

    /** Registered device row. */
    public static final class DeviceDto {
        @Nullable private final String deviceId;
        @Nullable private final String publicKey;
        @Nullable private final String createdAt;

        public DeviceDto() {
            this(null, null, null);
        }

        public DeviceDto(@Nullable String deviceId, @Nullable String publicKey,
                @Nullable String createdAt) {
            this.deviceId = deviceId;
            this.publicKey = publicKey;
            this.createdAt = createdAt;
        }

        @Nullable public String getDeviceId() {
            return deviceId;
        }

        @Nullable public String getPublicKey() {
            return publicKey;
        }

        @Nullable public String getCreatedAt() {
            return createdAt;
        }
    }

    /** {@code PUT /conversations/{id}/e2ee} body. */
    public static final class SetE2eeRequest {
        private final boolean enabled;

        public SetE2eeRequest(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() {
            return enabled;
        }
    }

    /** {@code GET|PUT /conversations/{id}/e2ee} response. */
    public static final class ConversationE2eeDto {
        private final boolean enabled;
        @Nullable private final List<MemberDeviceDto> devices;

        public ConversationE2eeDto() {
            this(false, List.of());
        }

        public ConversationE2eeDto(boolean enabled, @Nullable List<MemberDeviceDto> devices) {
            this.enabled = enabled;
            this.devices = devices;
        }

        public boolean isEnabled() {
            return enabled;
        }

        @NonNull public List<MemberDeviceDto> getDevices() {
            return devices == null ? List.of() : devices;
        }
    }

    /** One active device inside {@link ConversationE2eeDto}. */
    public static final class MemberDeviceDto {
        @Nullable private final String userId;
        @Nullable private final String deviceId;
        @Nullable private final String publicKey;

        public MemberDeviceDto() {
            this(null, null, null);
        }

        public MemberDeviceDto(@Nullable String userId, @Nullable String deviceId,
                @Nullable String publicKey) {
            this.userId = userId;
            this.deviceId = deviceId;
            this.publicKey = publicKey;
        }

        @Nullable public String getUserId() {
            return userId;
        }

        @Nullable public String getDeviceId() {
            return deviceId;
        }

        @Nullable public String getPublicKey() {
            return publicKey;
        }
    }
}
