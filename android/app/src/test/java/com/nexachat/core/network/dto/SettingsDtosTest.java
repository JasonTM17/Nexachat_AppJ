package com.nexachat.core.network.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.squareup.moshi.Moshi;
import org.junit.Test;

public final class SettingsDtosTest {
    private final Moshi moshi = new Moshi.Builder().build();

    @Test
    public void settingsResponseReadsNullableFlagAndAbsentField() throws Exception {
        UserSettingsDto present = moshi.adapter(UserSettingsDto.class)
                .fromJson("{\"showPresence\":false}");
        UserSettingsDto absent = moshi.adapter(UserSettingsDto.class).fromJson("{}");

        assertFalse(present.getShowPresence());
        assertNull(absent.getShowPresence());
    }

    @Test
    public void settingsPatchUsesTheWireName() {
        String json = moshi.adapter(UpdateUserSettingsRequest.class)
                .toJson(new UpdateUserSettingsRequest(true));

        assertEquals("{\"showPresence\":true}", json);
        assertTrue(json.contains("showPresence"));
    }
}
