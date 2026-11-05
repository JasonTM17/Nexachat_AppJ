package com.nexachat.summary.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.summary.ProviderResult;
import com.nexachat.summary.SummaryFallbackReason;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryMessage;
import com.nexachat.summary.SummaryProviderException;
import com.nexachat.summary.SummaryWindow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Opt-in, manual live check against the configured OpenAI-compatible provider.
 * Skipped unless {@code RUN_LIVE_LLM=1}; the runner scripts load the gitignored
 * {@code .env} into the process environment. Prints only status, latency, token
 * counts, the validation verdict and per-section item counts — never message
 * content, prompts or the API key.
 */
@Tag("live-llm")
@EnabledIfSystemProperty(named = "RUN_LIVE_LLM", matches = "1")
class LiveLlmSmoke {

    @Test
    void oneRealProviderCallOnSyntheticVietnameseConversation() {
        String apiKey = System.getenv("NEXACHAT_SUMMARY_LLM_API_KEY");
        String baseUrl = System.getenv("NEXACHAT_SUMMARY_LLM_BASE_URL");
        String model = System.getenv("NEXACHAT_SUMMARY_LLM_MODEL");
        String enabled = System.getenv("NEXACHAT_SUMMARY_LLM_ENABLED");
        if (isBlank(apiKey) || isBlank(baseUrl) || isBlank(model) || !"true".equalsIgnoreCase(enabled)) {
            throw new IllegalStateException("configuration unavailable");
        }

        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setApiKey(apiKey);
        properties.setBaseUrl(baseUrl);
        properties.setModel(model);
        properties.setTimeoutMs(30_000);
        properties.setMaxInputChars(24_000);
        properties.setMaxOutputTokens(4_000);

        List<SummaryMessage> messages = syntheticVietnameseConversation();
        SummaryWindow window = new SummaryWindow(UUID.randomUUID(), 1, messages.size(), messages);

        long started = System.nanoTime();
        String status = "PASS";
        String validation = "FAIL";
        String error = null;
        Integer promptTokens = null;
        Integer completionTokens = null;
        int keyPoints = 0;
        int decisions = 0;
        int actionItems = 0;
        int questions = 0;
        int topics = 0;

        LlmClient client = new LlmClient(properties, new ObjectMapper());
        try {
            Prompt prompt = new PromptBuilder(new ObjectMapper(), new Redactor())
                    .build(window, SummaryLanguage.VI, properties.getMaxInputChars());
            LlmClient.LlmCompletion completion = client.completeWithUsage(prompt);
            promptTokens = completion.promptTokens();
            completionTokens = completion.completionTokens();
            ProviderResult result = new LlmOutputValidator().validate(completion.content(), prompt, model);
            keyPoints = result.content().keyPoints().size();
            decisions = result.content().decisions().size();
            actionItems = result.content().actionItems().size();
            questions = result.content().questions().size();
            topics = result.content().topics().size();
            validation = "PASS";
        } catch (SummaryProviderException exception) {
            status = "FAIL";
            error = exception.fallbackReason() == SummaryFallbackReason.LLM_HTTP_ERROR
                    ? "LLM_HTTP_ERROR" : exception.fallbackReason().name();
        } catch (RuntimeException exception) {
            status = "FAIL";
            error = exception.getClass().getSimpleName();
        } finally {
            client.close();
        }

        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        System.out.println("status=" + status
                + " latency_ms=" + latencyMs
                + " prompt_tokens=" + (promptTokens == null ? "unknown" : promptTokens)
                + " completion_tokens=" + (completionTokens == null ? "unknown" : completionTokens)
                + " validation=" + validation
                + " keyPoints=" + keyPoints
                + " decisions=" + decisions
                + " actionItems=" + actionItems
                + " questions=" + questions
                + " topics=" + topics
                + (error == null ? "" : " error=" + error));
        if (!"PASS".equals(status) || !"PASS".equals(validation)) {
            throw new AssertionError("live llm smoke failed: status=" + status
                    + " validation=" + validation + " error=" + error);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static List<SummaryMessage> syntheticVietnameseConversation() {
        UUID[] senders = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        String[] names = {"An", "Bình", "Chi"};
        String[] texts = {
                "Chào cả nhóm, hôm nay mình cần chốt lịch triển khai phiên bản mới.",
                "Mình đã cập nhật tài liệu thiết kế ở trang dự án.",
                "Cảm ơn Bình, mình sẽ xem lại trước cuối ngày.",
                "Cuộc họp đánh giá rủi ro đã được xếp lúc 15 giờ ngày mai.",
                "Mình sẽ mời thêm hai kỹ sư phụ trách tải trung tâm.",
                "Ngân sách cho giai đoạn này đã được phê duyệt chưa?",
                "Ngân sách đã duyệt, tổng cộng hai mươi nghìn đô la.",
                "Quyết định: giữ nguyên lộ trình ba tuần và không thêm việc mới.",
                "Việc cần làm: hoàn tất bản nháp báo cáo trước thứ Ba.",
                "Anh Hùng sẽ chịu trách nhiệm phần kiểm thử hiệu năng.",
                "Liên hệ anh Hùng qua số 0912 345 678 hoặc email hung@example.com.",
                "Tài liệu tham khảo nằm ở https://example.com/docs/khao-sat.",
                "Mình nhận thấy phần xác thực còn mỏng, cần tăng cường ghi nhật ký.",
                "Đúng vậy, chúng ta nên thêm kiểm tra đầu vào ở tầng dịch vụ.",
                "Câu hỏi: ai sẽ là người duyệt bản cuối cùng?",
                "Bình và mình sẽ cùng duyệt, dự kiến cuối tuần này.",
                "Mình đề xuất tổ chức một buổi rà soát mã vào thứ Năm.",
                "Đồng ý, mình sẽ đặt phòng họp và gửi lịch cho mọi người.",
                "Nhắc lại, vấn đề độ trễ hiện tại ở khoảng ba mươi mili giây.",
                "Chúng ta đã giảm độ trễ từ một trăm xuống còn ba mươi.",
                "Việc cần làm: ghi lại chỉ số độ trễ vào bảng theo dõi.",
                "Quyết định: tạm dừng tính năng nhắc việc cho đến khi ổn định.",
                "Mình sẽ cập nhật phần thử nghiệm trên môi trường staging.",
                "Người dùng phản hồi rằng giao diện mới dễ nhìn hơn.",
                "Cần thêm hai ví dụ cụ thể vào phần hướng dẫn sử dụng.",
                "Mình sẽ phụ trách phần ví dụ và hoàn thành trước ngày mai.",
                "Câu hỏi: báo cáo cuối cùng cần bao nhiêu trang?",
                "Giới hạn trong mười trang, tập trung vào kết quả đo được.",
                "Mình sẽ gửi bản nháp cho mọi người trong buổi họp sáng.",
                "Tuyệt vời, nếu không có gì thay đổi mình xin phép chốt lịch."
        };
        List<SummaryMessage> messages = new ArrayList<>();
        for (int index = 0; index < texts.length; index++) {
            int sender = index % senders.length;
            messages.add(new SummaryMessage(UUID.randomUUID(), index + 1, senders[sender],
                    names[sender], texts[index], Instant.parse("2026-11-05T09:00:00Z"), null));
        }
        return messages;
    }
}
