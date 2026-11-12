package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;

import com.nexachat.R;
import org.junit.Test;

public final class ChatComposerEditPresentationTest {

    @Test
    public void editModeUsesCheckIconAndSaveChangesCopy() {
        assertEquals(R.drawable.ic_check, ChatComposerEditPresentation.sendIconRes(true));
        assertEquals(R.string.action_save_changes,
                ChatComposerEditPresentation.sendContentDescriptionRes(true));
    }

    @Test
    public void composeModeUsesSendIconAndSendDescription() {
        assertEquals(R.drawable.ic_send, ChatComposerEditPresentation.sendIconRes(false));
        assertEquals(R.string.cd_send, ChatComposerEditPresentation.sendContentDescriptionRes(false));
    }
}
