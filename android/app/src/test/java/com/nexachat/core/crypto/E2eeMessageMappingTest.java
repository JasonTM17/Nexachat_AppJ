package com.nexachat.core.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.crypto.E2eeMessageMapping.Resolved;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.ReplyRefDto;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/**
 * Ingest mapping: plaintext passthrough, encrypted-flag detection from either
 * the envelope field or the wire prefix, and safe degradation to
 * {@code decryptFailed} instead of leaking ciphertext into the UI.
 */
public final class E2eeMessageMappingTest {

    @Test
    public void plaintextPassesThrough() {
        MessageDto dto = message("hello", null);

        Resolved r = E2eeMessageMapping.resolve(dto, "c1", null, null);

        assertEquals("hello", r.content());
        assertFalse(r.encrypted());
        assertFalse(r.decryptFailed());
    }

    @Test
    public void encryptionFlagDecryptsViaSession() {
        MessageDto dto = message("e2ee1:wire", "E2EE_V1");
        E2eeSession session = mock(E2eeSession.class);
        when(session.decryptNow("c1", "u2", "m1", "e2ee1:wire", List.of()))
                .thenReturn(Optional.of("decoded"));

        Resolved r = E2eeMessageMapping.resolve(dto, "c1", "u2", session);

        assertEquals("decoded", r.content());
        assertTrue(r.encrypted());
        assertFalse(r.decryptFailed());
    }

    @Test
    public void wirePrefixCountsAsEncryptedWithoutFlag() {
        MessageDto dto = message("e2ee1:wire", null);

        Resolved r = E2eeMessageMapping.resolve(dto, "c1", "u2", null);

        assertNull(r.content());
        assertTrue(r.encrypted());
        assertTrue(r.decryptFailed());
    }

    @Test
    public void missingSessionOrSenderFailsSafe() {
        MessageDto dto = message("e2ee1:wire", "E2EE_V1");
        E2eeSession session = mock(E2eeSession.class);

        Resolved noSender = E2eeMessageMapping.resolve(dto, "c1", null, session);
        assertTrue(noSender.decryptFailed());
        assertNull(noSender.content());
    }

    @Test
    public void emptyDecryptResultMarksDecryptFailed() {
        MessageDto dto = message("e2ee1:wire", "E2EE_V1");
        E2eeSession session = mock(E2eeSession.class);
        when(session.decryptNow("c1", "u2", "m1", "e2ee1:wire", List.of()))
                .thenReturn(Optional.empty());

        Resolved r = E2eeMessageMapping.resolve(dto, "c1", "u2", session);

        assertNull(r.content());
        assertTrue(r.decryptFailed());
    }

    @Test
    public void deserializedNullEnvelopeEntriesStillFailSafe() throws Exception {
        // Moshi fills fields reflectively, so an untrusted [null] element can
        // reach the mapper even though the full constructor defensively copies.
        String json = "{\"id\":\"id-1\",\"seq\":1,\"conversationId\":\"c1\","
                + "\"senderId\":\"u2\",\"clientMessageId\":\"m1\",\"type\":\"TEXT\","
                + "\"content\":\"e2ee1:wire\",\"createdAt\":\"2024-01-01T00:00:00Z\","
                + "\"encryption\":\"E2EE_V1\",\"e2eeEnvelopes\":[null]}";
        MessageDto dto = new com.squareup.moshi.Moshi.Builder().build()
                .adapter(MessageDto.class).fromJson(json);
        E2eeSession session = mock(E2eeSession.class);
        when(session.decryptNow("c1", "u2", "m1", "e2ee1:wire", dto.getE2eeEnvelopes()))
                .thenReturn(Optional.empty());

        Resolved r = E2eeMessageMapping.resolve(dto, "c1", "u2", session);

        assertNull(r.content());
        assertTrue(r.encrypted());
        assertTrue(r.decryptFailed());
    }

    @Test
    public void replyTargetEncryptedFollowsFlag() {
        assertFalse(E2eeMessageMapping.replyTargetEncrypted(null));
        ReplyRefDto ref = new ReplyRefDto();
        assertFalse(E2eeMessageMapping.replyTargetEncrypted(ref));
        ReplyRefDto enc = new ReplyRefDto("m1", "u2", "Bob", null, null, "e2ee1:x", true);
        assertTrue(E2eeMessageMapping.replyTargetEncrypted(enc));
    }

    private static MessageDto message(String content, String encryption) {
        return new MessageDto("id-1", 1L, "c1", "u2", "Bob", null, "m1", "TEXT",
                content, null, null, List.of(), List.of(), "2024-01-01T00:00:00Z",
                null, null, List.of(), false, encryption, List.of());
    }
}
