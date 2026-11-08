package com.nexachat.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryCachePayloadTest {

    @Test
    void roundTripPreservesTheProviderLabel() throws Exception {
        SummaryGeneration generation = new SummaryGeneration(content(), SummaryLanguage.EN, "LLM",
                "fallback-model", null, "fallback");
        ObjectMapper objectMapper = new ObjectMapper();

        String json = objectMapper.writeValueAsString(SummaryCachePayload.from(generation));
        SummaryCachePayload payload = objectMapper.readValue(json, SummaryCachePayload.class);

        assertThat(payload.provider()).isEqualTo("fallback");
        assertThat(payload.toGeneration().provider()).isEqualTo("fallback");
    }

    @Test
    void legacyPayloadWithoutAProviderDeserializesToNull() throws Exception {
        String legacy = "{\"language\":\"EN\",\"generatedBy\":\"LLM\",\"model\":\"primary-model\","
                + "\"fallbackReason\":null,\"content\":{\"overview\":\"Overview\",\"keyPoints\":[],"
                + "\"decisions\":[],\"actionItems\":[],\"questions\":[],\"topics\":[],\"participants\":[]}}";

        SummaryCachePayload payload = new ObjectMapper().readValue(legacy, SummaryCachePayload.class);

        assertThat(payload.provider()).isNull();
        assertThat(payload.toGeneration().provider()).isNull();
    }

    private static SummaryContent content() {
        return new SummaryContent("Overview", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
