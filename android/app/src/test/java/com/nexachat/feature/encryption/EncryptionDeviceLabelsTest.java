package com.nexachat.feature.encryption;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;
import org.junit.Test;

public final class EncryptionDeviceLabelsTest {

    private static MemberDeviceDto device(String userId, String deviceId) {
        return new MemberDeviceDto(userId, deviceId, "key");
    }

    @Test
    public void thisPhysicalDeviceIsOwnByDeviceId() {
        MemberDeviceDto d = device("user-1", "dev-a");
        assertTrue(EncryptionDeviceLabels.isOwnDevice(d, "user-1", "dev-a"));
    }

    @Test
    public void anotherDeviceOnSameAccountIsOwnByUserId() {
        MemberDeviceDto d = device("user-1", "dev-b");
        assertTrue(EncryptionDeviceLabels.isOwnDevice(d, "user-1", "dev-a"));
    }

    @Test
    public void peerDeviceIsNotOwn() {
        MemberDeviceDto d = device("user-2", "dev-x");
        assertFalse(EncryptionDeviceLabels.isOwnDevice(d, "user-1", "dev-a"));
    }

    @Test
    public void peerDeviceStaysPeerWhenCallerIdentityMissing() {
        MemberDeviceDto d = device("user-2", "dev-x");
        assertFalse(EncryptionDeviceLabels.isOwnDevice(d, null, null));
    }

    @Test
    public void nullDeviceIsNotOwn() {
        assertFalse(EncryptionDeviceLabels.isOwnDevice(null, "user-1", "dev-a"));
    }
}
