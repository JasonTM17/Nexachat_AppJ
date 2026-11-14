package com.nexachat.feature.chat.system;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemEventParserTest {

    @Test
    public void recognizesCallStructuredPayload() {
        SystemEventPayload payload = SystemEventParser.parse("CALL|COMPLETED|42|peer");
        assertEquals(SystemEventType.CALL_LOG, payload.getType());
        assertEquals("CALL|COMPLETED|42|peer", payload.getArguments()[0]);
    }

    @Test
    public void recognizesEvtStructuredPayloadAndRejectsMissingArgs() {
        SystemEventPayload ok = SystemEventParser.parse("EVT|GROUP_MEMBER_ADDED|Alice|Bob");
        assertEquals(SystemEventType.GROUP_MEMBER_ADDED, ok.getType());
        assertEquals("Alice", ok.getArguments()[0]);
        assertEquals("Bob", ok.getArguments()[1]);

        SystemEventPayload missing = SystemEventParser.parse("EVT|GROUP_MEMBER_ADDED|Alice");
        assertFalse(missing.isLocalized());
        assertEquals("EVT|GROUP_MEMBER_ADDED|Alice", missing.getFallbackText());
    }

    @Test
    public void mapsLegacyEnglishTemplates() {
        assertType(SystemEventType.GROUP_CREATED,
                SystemEventParser.parse("Minh created the group"), "Minh");
        assertType(SystemEventType.GROUP_MEMBER_ADDED,
                SystemEventParser.parse("An added Binh"), "An", "Binh");
        assertType(SystemEventType.GROUP_MEMBER_APPROVED,
                SystemEventParser.parse("An approved Binh to join the group"), "An", "Binh");
        assertType(SystemEventType.GROUP_MEMBER_INVITE_JOINED,
                SystemEventParser.parse("Binh joined the group via invite link"), "Binh");
        assertType(SystemEventType.GROUP_MEMBER_LEFT,
                SystemEventParser.parse("An left the group"), "An");
        assertType(SystemEventType.GROUP_MEMBER_REMOVED,
                SystemEventParser.parse("An removed Binh"), "An", "Binh");
        assertType(SystemEventType.GROUP_RENAMED,
                SystemEventParser.parse("An renamed the group to \"Study crew\""), "An", "Study crew");
        assertType(SystemEventType.GROUP_ROLE_CHANGED,
                SystemEventParser.parse("An changed Binh's role"), "An", "Binh");
    }

    @Test
    public void unknownContentFallsBackToServerText() {
        SystemEventPayload payload = SystemEventParser.parse("Custom announcement");
        assertFalse(payload.isLocalized());
        assertEquals("Custom announcement", payload.getFallbackText());
    }

  @Test
    public void blankContentIsUnknownEmpty() {
        SystemEventPayload payload = SystemEventParser.parse("   ");
        assertFalse(payload.isLocalized());
        assertEquals("", payload.getFallbackText());
    }

    @Test
    public void mapsLegacyE2eeAndAdminPromotionTemplates() {
        assertType(SystemEventType.E2EE_ENABLED,
                SystemEventParser.parse("An turned on end-to-end encryption"), "An");
        assertType(SystemEventType.E2EE_DISABLED,
                SystemEventParser.parse("An turned off end-to-end encryption"), "An");
        assertType(SystemEventType.GROUP_ADMIN_PROMOTED,
                SystemEventParser.parse("An made Binh a group admin"), "An", "Binh");
    }

    @Test
    public void adminPromotionIsNotParsedAsMemberAdded() {
        SystemEventPayload payload = SystemEventParser.parse("An made Binh a group admin");
        assertEquals(SystemEventType.GROUP_ADMIN_PROMOTED, payload.getType());
        assertFalse(SystemEventType.GROUP_MEMBER_ADDED == payload.getType());
    }

    @Test
    public void recognizesNewStructuredEvtForms() {
        assertType(SystemEventType.E2EE_ENABLED,
                SystemEventParser.parse("EVT|E2EE_ENABLED|An"), "An");
        assertType(SystemEventType.E2EE_DISABLED,
                SystemEventParser.parse("EVT|E2EE_DISABLED|An"), "An");
        assertType(SystemEventType.GROUP_ADMIN_PROMOTED,
                SystemEventParser.parse("EVT|GROUP_ADMIN_PROMOTED|An|Binh"), "An", "Binh");

        SystemEventPayload missing = SystemEventParser.parse("EVT|GROUP_ADMIN_PROMOTED|An");
        assertFalse(missing.isLocalized());
        SystemEventPayload missingName = SystemEventParser.parse("EVT|E2EE_ENABLED");
        assertFalse(missingName.isLocalized());
    }

    private static void assertType(SystemEventType expected, SystemEventPayload payload, String... args) {
        assertTrue(payload.isLocalized());
        assertEquals(expected, payload.getType());
        assertEquals(args.length, payload.getArguments().length);
        for (int i = 0; i < args.length; i++) {
            assertEquals(args[i], payload.getArguments()[i]);
        }
    }
}
