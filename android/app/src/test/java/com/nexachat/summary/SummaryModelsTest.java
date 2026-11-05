package com.nexachat.summary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.squareup.moshi.Moshi;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class SummaryModelsTest {
    @Test
    public void responseModelParsesAndNormalizesNullArrays() throws Exception {
        String json = "{\"summaryId\":\"summary-1\",\"conversationId\":\"conversation-1\","
                + "\"language\":\"VI\",\"keyPoints\":null,\"decisions\":[],"
                + "\"actionItems\":null,\"questions\":null,\"topics\":null,"
                + "\"participants\":null}";

        SummaryResponse response = new Moshi.Builder().build()
                .adapter(SummaryResponse.class)
                .fromJson(json);

        assertNotNull(response);
        assertEquals("summary-1", response.getSummaryId());
        assertEquals("conversation-1", response.getConversationId());
        assertEquals(SummaryLanguage.VI, response.getLanguage());
        assertEquals(List.of(), response.getKeyPoints());
        assertEquals(List.of(), response.getActionItems());
        assertEquals(List.of(), response.getQuestions());
        assertEquals(List.of(), response.getTopics());
        assertEquals(List.of(), response.getParticipants());
    }

    @Test
    public void requestModelSerializesContractNamesAndKeepsListsImmutable() throws Exception {
        SummaryRequest request = new SummaryRequest(
                SummaryScope.LAST_N, 25, SummaryLanguage.EN, true);
        String json = new Moshi.Builder().build().adapter(SummaryRequest.class).toJson(request);
        SummaryItem item = new SummaryItem("Decision", new ArrayList<>(List.of("message-1")));

        assertTrue(json.contains("\"scope\":\"LAST_N\""));
        assertTrue(json.contains("\"limit\":25"));
        assertTrue(json.contains("\"language\":\"EN\""));
        assertTrue(json.contains("\"refresh\":true"));
        assertThrows(UnsupportedOperationException.class,
                () -> item.getMessageIds().add("message-2"));
    }
}
