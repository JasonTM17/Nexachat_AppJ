package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.nexachat.feature.chat.VoiceRecordPermissionHelper.ResultAction;
import com.nexachat.feature.chat.VoiceRecordPermissionHelper.TapAction;
import org.junit.Test;

public final class VoicePermissionRecoveryTest {

    @Test
    public void deniedTapRequestsPermissionBeforePermanentBlock() {
        assertEquals(TapAction.REQUEST_PERMISSION, VoiceRecordPermissionHelper.tapAction(false, false, false));
        assertEquals(ResultAction.DENIED_SNACKBAR,
                VoiceRecordPermissionHelper.permissionResultAction(false, true, false));
    }

    @Test
    public void permanentlyDeniedTapOffersSettingsInsteadOfRelaunching() {
        assertEquals(TapAction.OPEN_SETTINGS, VoiceRecordPermissionHelper.tapAction(false, false, true));
        assertEquals(ResultAction.PERMANENT_DENIAL_SNACKBAR,
                VoiceRecordPermissionHelper.permissionResultAction(false, false, true));
    }

    @Test
    public void rationaleTapExplainsBeforeRequesting() {
        assertEquals(TapAction.SHOW_RATIONALE, VoiceRecordPermissionHelper.tapAction(false, true, false));
    }

    @Test
    public void grantedReturnDoesNotAutoStartRecording() {
        assertEquals(TapAction.PROCEED, VoiceRecordPermissionHelper.tapAction(true, false, true));
        assertEquals(ResultAction.NONE,
                VoiceRecordPermissionHelper.permissionResultAction(true, false, true));
        assertFalse(VoiceRecordPermissionHelper.shouldBeginRecordingAfterPermissionGrant());
    }
}
