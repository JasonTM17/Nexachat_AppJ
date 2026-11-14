package com.nexachat.e2ee;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.e2ee.dto.E2eeEnvelopeRequest;
import com.nexachat.e2ee.dto.E2eeEnvelopeResponse;

import java.util.List;

/**
 * JSONB codec for the {@code {deviceId, wrappedKey}} envelope array stored on
 * {@code messages.e2ee_envelopes}. Wire and storage share one shape, so a
 * single mapper both writes the insert payload and reads it back.
 */
public final class E2eeEnvelopes {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<E2eeEnvelopeResponse>> ENVELOPE_LIST =
            new TypeReference<>() {
            };

    private E2eeEnvelopes() {
    }

    /** Serializes validated request envelopes for the JSONB column. */
    public static String toJson(List<E2eeEnvelopeRequest> envelopes) {
        try {
            return MAPPER.writeValueAsString(envelopes == null ? List.of() : envelopes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Envelope serialization failed", e);
        }
    }

    /** Parses a stored envelope array; NULL (plaintext rows) yields an empty list. */
    public static List<E2eeEnvelopeResponse> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, ENVELOPE_LIST);
        } catch (JsonProcessingException e) {
            // A corrupt row must not break message reads; surface an empty list
            // so clients still render the (undecryptable) ciphertext body.
            return List.of();
        }
    }
}
