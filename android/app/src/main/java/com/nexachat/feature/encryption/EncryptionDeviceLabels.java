package com.nexachat.feature.encryption;

import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;

/** Owner rules for the encryption screen's device rows. */
public final class EncryptionDeviceLabels {

    private EncryptionDeviceLabels() {
    }

    /**
     * True when the device belongs to the signed-in caller — either this
     * physical device or another device registered under the same account.
     * Rows that fail this check are labelled with the peer's display name.
     */
    public static boolean isOwnDevice(@Nullable MemberDeviceDto device,
            @Nullable String myUserId, @Nullable String currentDeviceId) {
        if (device == null) {
            return false;
        }
        if (currentDeviceId != null && currentDeviceId.equals(device.getDeviceId())) {
            return true;
        }
        return myUserId != null && myUserId.equals(device.getUserId());
    }
}
