package com.nexachat.core.network.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.squareup.moshi.Moshi;
import java.util.List;
import org.junit.Test;

/** Wire contract between Android translate DTOs and the server's JSON shapes. */
public final class TranslateDtosTest {
    private final Moshi moshi = new Moshi.Builder().build();

    @Test
    public void translateResponseReadsServerFields() throws Exception {
        TranslateResponseDto dto = moshi.adapter(TranslateResponseDto.class).fromJson(
                "{\"text\":\"xin chào\",\"sourceLang\":\"en\",\"targetLang\":\"vi\","
                        + "\"cached\":false,\"model\":\"test-model\"}");

        assertEquals("xin chào", dto.getText());
        assertEquals("en", dto.getSourceLang());
        assertEquals("vi", dto.getTargetLang());
        assertFalse(dto.isCached());
        assertEquals("test-model", dto.getModel());
    }

    @Test
    public void translateRequestUsesWireNames() {
        String json = moshi.adapter(TranslateRequest.class)
                .toJson(new TranslateRequest("vi"));

        assertEquals("{\"targetLang\":\"vi\"}", json);
    }

    @Test
    public void batchItemReadsErrorAndNullCached() throws Exception {
        TranslateBatchResponse dto = moshi.adapter(TranslateBatchResponse.class).fromJson(
                "{\"results\":["
                        + "{\"messageId\":\"m1\",\"text\":\"ok\",\"sourceLang\":\"en\","
                        + "\"targetLang\":\"vi\",\"cached\":true,\"model\":\"m\"},"
                        + "{\"messageId\":\"m2\",\"text\":null,\"sourceLang\":null,"
                        + "\"targetLang\":\"vi\",\"cached\":null,\"model\":null,"
                        + "\"error\":\"TRANSLATION_REFUSED\"}]}");

        assertEquals(2, dto.getResults().size());
        TranslateBatchResponse.Item first = dto.getResults().get(0);
        assertEquals("m1", first.getMessageId());
        assertEquals("ok", first.getText());
        assertTrue(first.isCached());
        assertNull(first.getError());

        TranslateBatchResponse.Item second = dto.getResults().get(1);
        assertEquals("m2", second.getMessageId());
        assertNull(second.getText());
        assertFalse(second.isCached());
        assertEquals("TRANSLATION_REFUSED", second.getError());
    }

    @Test
    public void batchRequestSerializesIdsAndTarget() {
        String json = moshi.adapter(TranslateBatchRequest.class)
                .toJson(new TranslateBatchRequest(List.of("a", "b"), "en"));

        assertTrue(json.contains("\"messageIds\":[\"a\",\"b\"]"));
        assertTrue(json.contains("\"targetLang\":\"en\""));
    }

    @Test
    public void prefsRoundTripUsesServerFieldNames() throws Exception {
        TranslatePrefsDto dto = moshi.adapter(TranslatePrefsDto.class).fromJson(
                "{\"conversationId\":\"c1\",\"autoTranslate\":true,\"targetLang\":\"en\"}");

        assertTrue(dto.isAutoTranslate());
        assertEquals("en", dto.getTargetLang());

        // PUT is a full replace: a null targetLang may be omitted and still
        // clears the per-conversation override server-side.
        String json = moshi.adapter(UpdateTranslatePrefsRequest.class)
                .toJson(new UpdateTranslatePrefsRequest(false, null));
        assertTrue(json.contains("\"autoTranslate\":false"));
        assertFalse(json.contains("\"targetLang\":\"en\""));
    }

    @Test
    public void glossaryTermsKeepReplacementNullWhenVerbatim() throws Exception {
        TranslationGlossaryDto dto = moshi.adapter(TranslationGlossaryDto.class).fromJson(
                "{\"terms\":[{\"term\":\"NexaChat\",\"replacement\":null},"
                        + "{\"term\":\"sếp\",\"replacement\":\"boss\"}]}");

        assertEquals(2, dto.getTerms().size());
        assertEquals("NexaChat", dto.getTerms().get(0).getTerm());
        assertNull(dto.getTerms().get(0).getReplacement());
        assertEquals("boss", dto.getTerms().get(1).getReplacement());
    }
}
