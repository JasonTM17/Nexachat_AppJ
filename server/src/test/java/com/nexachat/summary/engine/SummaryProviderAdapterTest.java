package com.nexachat.summary.engine;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryProviderAdapterTest {
    @Test
    void implementsTheServerProviderContractAndMapsCitationsBackToUuidDtos() {
        LocalExtractiveSummaryProvider provider = new LocalExtractiveSummaryProvider();
        UUID conversationId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID thirdId = UUID.randomUUID();
        UUID senderA = UUID.randomUUID();
        UUID senderB = UUID.randomUUID();
        com.nexachat.summary.SummaryWindow window = new com.nexachat.summary.SummaryWindow(
                conversationId,
                1,
                3,
                List.of(
                        new com.nexachat.summary.SummaryMessage(firstId, 1, senderA, "Người A",
                                "Mọi người thống nhất dùng bảng màu xanh.", null, null),
                        new com.nexachat.summary.SummaryMessage(secondId, 2, senderB, "Người B",
                                "Nhớ gửi ảnh trước thứ 6.", null, null),
                        new com.nexachat.summary.SummaryMessage(thirdId, 3, senderA, "Người A",
                                "Ai sẽ kiểm tra phần tương phản?", null, null)));

        com.nexachat.summary.ProviderResult result = provider.summarize(
                window, com.nexachat.summary.SummaryLanguage.AUTO);
        Set<UUID> inputIds = Set.of(firstId, secondId, thirdId);
        Set<UUID> citations = new HashSet<>();
        result.content().keyPoints().forEach(item -> citations.addAll(item.messageIds()));
        result.content().decisions().forEach(item -> citations.addAll(item.messageIds()));
        result.content().questions().forEach(item -> citations.addAll(item.messageIds()));
        result.content().actionItems().forEach(item -> citations.addAll(item.messageIds()));

        assertEquals("LOCAL", provider.id());
        assertEquals(com.nexachat.summary.SummaryLanguage.VI, result.language());
        assertNull(result.model());
        assertTrue(inputIds.containsAll(citations));
        assertTrue(result.content().decisions().stream().anyMatch(item -> item.messageIds().contains(firstId)));
        assertTrue(result.content().actionItems().stream().anyMatch(item -> item.messageIds().contains(secondId)));
    }
}
