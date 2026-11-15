package com.nexachat.feature.encryption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;
import java.util.List;
import org.junit.Test;

public final class EncryptionStatusRulesTest {

    private static EncryptionViewModel.UiState state(
            String errorCode, List<MemberDeviceDto> devices, String safetyCode) {
        return new EncryptionViewModel.UiState(
                false, false, false, true, errorCode, devices, safetyCode);
    }

    private static MemberDeviceDto device(String userId, String deviceId) {
        return new MemberDeviceDto(userId, deviceId, "a2V5");
    }

    @Test
    public void serverFailuresUseTheErrorColor() {
        EncryptionViewModel.UiState s = state("E2EE_NETWORK", List.of(), null);
        assertEquals(com.google.android.material.R.attr.colorError,
                EncryptionFragment.StatusRules.statusTextColorAttr(s));
    }

    @Test
    public void theRestingUnavailableNoteIsInformational() {
        EncryptionViewModel.UiState s = state(null, List.of(), null);
        assertEquals(com.google.android.material.R.attr.colorOnSurfaceVariant,
                EncryptionFragment.StatusRules.statusTextColorAttr(s));
    }

    @Test
    public void listedDevicesWithoutAComparisonCodeMeanRowsWereDropped() {
        EncryptionViewModel.UiState s = state(null,
                List.of(device("u1", "dev-a"), device("u2", "dev-b")), null);
        assertTrue(EncryptionFragment.StatusRules.showDirectoryHint(s));
    }

    @Test
    public void aCompleteDirectoryWithACodeShowsNoHint() {
        EncryptionViewModel.UiState s = state(null,
                List.of(device("u1", "dev-a"), device("u2", "dev-b")), "code");
        assertFalse(EncryptionFragment.StatusRules.showDirectoryHint(s));
    }

    @Test
    public void aSmallDirectoryNeedsNoDroppedRowHint() {
        EncryptionViewModel.UiState s =
                state(null, List.of(device("u1", "dev-a")), null);
        assertFalse(EncryptionFragment.StatusRules.showDirectoryHint(s));
    }

    @Test
    public void anEmptyDirectoryShowsNoHint() {
        EncryptionViewModel.UiState s = state(null, List.of(), null);
        assertFalse(EncryptionFragment.StatusRules.showDirectoryHint(s));
    }
}
