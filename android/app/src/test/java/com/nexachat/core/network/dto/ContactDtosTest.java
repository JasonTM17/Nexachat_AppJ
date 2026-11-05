package com.nexachat.core.network.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.squareup.moshi.Moshi;
import org.junit.Test;

public final class ContactDtosTest {
    @Test
    public void bareContactEnvelopeKeepsCreatedAtOutOfAvatarUrl() throws Exception {
        String capturedContactJson = "{\"contactUserId\":\"user-redacted\","
                + "\"phone\":\"+66000000000\",\"displayName\":\"Peer\","
                + "\"aliasName\":null,\"avatarUrl\":null,"
                + "\"createdAt\":\"2026-11-03T10:00:00Z\"}";
        ContactEnvelopeDto envelope = new Moshi.Builder()
                .build()
                .adapter(ContactEnvelopeDto.class)
                .fromJson(capturedContactJson);
        assertNotNull(envelope);

        ContactDto contact = envelope.unwrap();

        assertEquals("2026-11-03T10:00:00Z", contact.getCreatedAt());
        assertNull(contact.getAvatarUrl());
    }
}
