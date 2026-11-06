package com.nexachat.feature.summary;

import static org.junit.Assert.assertEquals;

import com.nexachat.summary.SummaryActionItem;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SummaryActionKeyHelperTest {

    @Test
    public void getActionItemKey_withTextAndFirstCitation_combinesWithColon() {
        SummaryActionItem action = new SummaryActionItem(
                "Lan gửi biên bản.",
                null,
                null,
                List.of("message-3", "message-4")
        );
        String key = SummaryActionKeyHelper.getActionItemKey(action);
        assertEquals("Lan gửi biên bản.:message-3", key);
    }

    @Test
    public void getActionItemKey_withoutCitations_returnsTextOnly() {
        SummaryActionItem action = new SummaryActionItem(
                "Chốt hợp đồng dự án.",
                null,
                null,
                Collections.emptyList()
        );
        String key = SummaryActionKeyHelper.getActionItemKey(action);
        assertEquals("Chốt hợp đồng dự án.", key);
    }

    @Test
    public void getActionItemKey_withMultipleCitations_usesFirstCitationOnly() {
        SummaryActionItem action = new SummaryActionItem(
                "Chuẩn bị tài liệu",
                "user-1",
                "10:00",
                List.of("uuid-a", "uuid-b", "uuid-c")
        );
        String key = SummaryActionKeyHelper.getActionItemKey(action);
        assertEquals("Chuẩn bị tài liệu:uuid-a", key);
    }

    @Test
    public void getActionItemKey_withNullAction_returnsEmptyString() {
        String key = SummaryActionKeyHelper.getActionItemKey(null);
        assertEquals("", key);
    }

    @Test
    public void getActionItemKey_withNullCitations_returnsTextOnly() {
        SummaryActionItem action = new SummaryActionItem("Họp lúc 9h.", null, null, null);
        String key = SummaryActionKeyHelper.getActionItemKey(action);
        assertEquals("Họp lúc 9h.", key);
    }

    @Test
    public void getActionItemKey_withWhitespace_trimsBothProperly() {
        SummaryActionItem action = new SummaryActionItem(
                "   Gửi email báo cáo   ",
                null,
                null,
                List.of("   msg-99   ")
        );
        String key = SummaryActionKeyHelper.getActionItemKey(action);
        assertEquals("Gửi email báo cáo:msg-99", key);
    }

    @Test
    public void getActionItemKey_skipsBlankCitationToFindFirstValidCitation() {
        SummaryActionItem action = new SummaryActionItem(
                "Task cần xử lý",
                null,
                null,
                List.of("   ", "msg-valid-1")
        );
        String key = SummaryActionKeyHelper.getActionItemKey(action);
        assertEquals("Task cần xử lý:msg-valid-1", key);
    }

    @Test
    public void buildKey_withExplicitStringCitation() {
        assertEquals("Task A:msg-1", SummaryActionKeyHelper.buildKey("Task A", "msg-1"));
        assertEquals("Task A", SummaryActionKeyHelper.buildKey("Task A", (String) null));
        assertEquals("Task A", SummaryActionKeyHelper.buildKey("Task A", "   "));
        assertEquals(":msg-1", SummaryActionKeyHelper.buildKey("", "msg-1"));
        assertEquals(":msg-1", SummaryActionKeyHelper.buildKey(null, "msg-1"));
        assertEquals("", SummaryActionKeyHelper.buildKey(null, (String) null));
    }

    @Test
    public void buildKey_withMessageIdList() {
        assertEquals("Task B:msg-2", SummaryActionKeyHelper.buildKey("Task B", List.of("msg-2")));
        assertEquals("Task B", SummaryActionKeyHelper.buildKey("Task B", Collections.emptyList()));
        assertEquals("Task B", SummaryActionKeyHelper.buildKey("Task B", (List<String>) null));
    }
}
