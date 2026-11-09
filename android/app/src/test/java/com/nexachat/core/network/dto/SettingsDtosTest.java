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

    @Test
    public void settingsResponseReadsNullableShowSeenAndAbsentField() throws Exception {
        UserSettingsDto present = moshi.adapter(UserSettingsDto.class)
                .fromJson("{\"showPresence\":true,\"showSeen\":false}");
        UserSettingsDto absent = moshi.adapter(UserSettingsDto.class).fromJson("{}");

        assertTrue(present.getShowPresence());
        assertFalse(present.getShowSeen());
        assertNull(absent.getShowSeen());
    }

    @Test
    public void settingsPatchWithShowSeenUsesWireNames() {
        String jsonSeenOnly = moshi.adapter(UpdateUserSettingsRequest.class)
                .toJson(new UpdateUserSettingsRequest(null, false));
        assertEquals("{\"showSeen\":false}", jsonSeenOnly);

        String jsonBoth = moshi.adapter(UpdateUserSettingsRequest.class)
                .toJson(new UpdateUserSettingsRequest(true, false));
        assertTrue(jsonBoth.contains("\"showPresence\":true"));
        assertTrue(jsonBoth.contains("\"showSeen\":false"));
    }
}
