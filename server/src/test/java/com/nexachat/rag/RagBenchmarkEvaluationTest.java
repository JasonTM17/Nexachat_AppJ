package com.nexachat.rag;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.llm.Redactor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Synthetic Vietnamese + English benchmark with gold message ids. Reports
 * recall@3/@5, MRR, citation validity, zero-LLM share and input-char
 * reduction against the whole-window baseline.
 */
class RagBenchmarkEvaluationTest {

    private static final int CONVERSATION_COUNT = 32;
    private static final int MESSAGES_PER_CONVERSATION = 200;
    private static final int FACTS_PER_CONVERSATION = 6;
    private static final double MIN_RECALL_AT_5 = 0.85;
    private static final double MIN_RECALL_AT_3 = 0.70;
    private static final double MIN_ZERO_LLM_RATIO = 0.40;
    private static final double MAX_INPUT_CHAR_RATIO = 0.30;

    private static final String[] EN_TOPICS = {
            "deployment window", "budget", "kickoff meeting", "support escalations",
            "release version", "office location", "security review", "hiring plan"};
    private static final String[] EN_VALUES = {
            "Friday at 10am", "1200 dollars", "Monday 9am", "two hours",
            "2.4.1", "the Hanoi office", "March 3", "three engineers"};
    private static final String[] VI_TOPICS = {
            "cuộc họp khởi động", "ngân sách", "phiên bản phát hành", "địa điểm văn phòng",
            "kế hoạch tuyển dụng", "chi phí tổ chức", "bai bản báo cáo", "lịch trình"};
    private static final String[] VI_VALUES = {
            "thứ Hai lúc 9 giờ", "1200 đô la", "2.4.1", "văn phòng Hà Nội",
            "ba kỹ sư", "500 USD", "bản cập nhật", "thứ Sáu tuần sau"};
    private static final String[] EN_FILLERS = {
            "Let's discuss the agenda for today.",
            "Please review the notes before the next sync.",
            "The team agreed to keep the current plan.",
            "We will share the update after the call.",
            "The draft is attached for everyone to read."};
    private static final String[] VI_FILLERS = {
            "Chúng ta bàn về chương trình hôm nay.",
            "Hãy xem lại ghi chú trước cuộc họp.",
            "Nhóm đã đồng ý giữ kế hoạch hiện tại.",
            "Chúng ta sẽ chia sẻ cập nhật sau cuộc gọi.",
            "Bản nháp được đính kèm để mọi người đọc."};
    private static final String[] EN_NON_LOOKUP = {
            "Summarize everything we decided.",
            "List the action items.",
            "Give me an overview of the plan."};
    private static final String[] VI_NON_LOOKUP = {
            "Tóm tắt những gì chúng ta đã quyết định.",
            "Liệt kê các việc cần làm.",
            "Cho tìm tổng quan về kế hoạch."};

    @Test
    void benchmarkMeetsRetrievalAndCostTargets() {
        SyntheticCorpus corpus = SyntheticCorpus.generate();
        RagRetrievalService retrieval = RagTestSupport.retrieval(corpus.index(), new RagProperties());

        RetrievalMetrics retrievalMetrics = evaluateRetrieval(corpus, retrieval);
        RouterMetrics routerMetrics = evaluateRouter(corpus);

        System.out.printf(
                "rag-eval: conversations=%d messages=%d lookupQuestions=%d "
                        + "recall@3=%.3f recall@5=%.3f mrr=%.3f citationValidity=%.3f "
                        + "zeroLlm=%.1f%% meanInputChars=%.1f baselineChars=%.1f reduction=%.1f%%%n",
                CONVERSATION_COUNT,
                CONVERSATION_COUNT * MESSAGES_PER_CONVERSATION,
                corpus.lookupQuestions().size(),
                retrievalMetrics.recallAt3(),
                retrievalMetrics.recallAt5(),
                retrievalMetrics.mrr(),
                retrievalMetrics.citationValidity(),
                routerMetrics.zeroLlmRatio() * 100.0,
                routerMetrics.meanInputChars(),
                routerMetrics.meanBaselineChars(),
                routerMetrics.reductionPercent());

        assertThat(corpus.lookupQuestions().size()).isGreaterThanOrEqualTo(150);
        assertThat(retrievalMetrics.recallAt5()).isGreaterThanOrEqualTo(MIN_RECALL_AT_5);
        assertThat(retrievalMetrics.recallAt3()).isGreaterThanOrEqualTo(MIN_RECALL_AT_3);
        assertThat(retrievalMetrics.citationValidity()).isEqualTo(1.0);
        assertThat(routerMetrics.zeroLlmRatio()).isGreaterThanOrEqualTo(MIN_ZERO_LLM_RATIO);
        assertThat(routerMetrics.meanInputChars())
                .isLessThanOrEqualTo(routerMetrics.meanBaselineChars() * MAX_INPUT_CHAR_RATIO);
    }

    private RetrievalMetrics evaluateRetrieval(SyntheticCorpus corpus, RagRetrievalService retrieval) {
        int hitsAt3 = 0;
        int hitsAt5 = 0;
        double reciprocalRank = 0.0;
        int validCitations = 0;
        int totalCitations = 0;
        for (LookupQuestion question : corpus.lookupQuestions()) {
            RetrievalResult result = retrieval.retrieve(question.conversationId(),
                    question.question(), question.language(), false);
            int rank = -1;
            for (int index = 0; index < result.ranked().size(); index++) {
                if (result.ranked().get(index).message().id().equals(question.goldMessageId())) {
                    rank = index + 1;
                    break;
                }
            }
            if (rank > 0) {
                if (rank <= 3) {
                    hitsAt3++;
                }
                if (rank <= 5) {
                    hitsAt5++;
                }
                reciprocalRank += 1.0 / rank;
            }
            Set<UUID> conversationIds = corpus.messageIds().get(question.conversationId());
            for (RagContextItem item : result.context()) {
                for (UUID messageId : item.messageIds()) {
                    totalCitations++;
                    if (conversationIds.contains(messageId)) {
                        validCitations++;
                    }
                }
            }
        }
        int questionCount = corpus.lookupQuestions().size();
        return new RetrievalMetrics(
                (double) hitsAt3 / questionCount,
                (double) hitsAt5 / questionCount,
                reciprocalRank / questionCount,
                totalCitations == 0 ? 1.0 : (double) validCitations / totalCitations);
    }

    private RouterMetrics evaluateRouter(SyntheticCorpus corpus) {
        InMemoryRagMessageIndex index = corpus.index();
        RagProperties properties = new RagProperties();
        RagStats stats = new RagStats();
        RagEmbeddingService embeddings = new RagEmbeddingService(
                new RagEmbeddingProviders(properties), index, new VectorCache(4096),
                properties, stats, new Redactor());
        RagRetrievalService retrieval = new RagRetrievalService(index, embeddings, properties);

        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ConversationMemberRepository memberRepository = mock(ConversationMemberRepository.class);
        SummaryConsentRepository consentRepository = mock(SummaryConsentRepository.class);
        RagQuestionCache questionCache = mock(RagQuestionCache.class);
        RagLlmAnswerService llmAnswerService = mock(RagLlmAnswerService.class);
        AskRateLimiter rateLimiter = mock(AskRateLimiter.class);

        when(conversationRepository.existsById(any())).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(any(), any())).thenReturn(true);
        when(consentRepository.isConsented(any())).thenReturn(true);
        when(questionCache.citationsValid(any(), any())).thenReturn(true);
        when(llmAnswerService.answer(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            List<RagContextItem> context = invocation.getArgument(4);
            if (context.isEmpty()) {
                return null;
            }
            return new RagLlmAnswer("Synthetic answer.", context.get(0).messageIds());
        });

        AskService service = new AskService(
                rateLimiter, conversationRepository, memberRepository, consentRepository,
                embeddings, retrieval, questionCache, llmAnswerService, properties, stats,
                Clock.systemUTC());

        UUID userId = UUID.randomUUID();
        int llmCalls = 0;
        int zeroLlm = 0;
        double inputChars = 0.0;
        double baselineChars = 0.0;
        Set<UUID> allCited = new HashSet<>();
        Set<UUID> citedValid = new HashSet<>();
        int requests = 0;

        for (BenchmarkQuestion question : corpus.questions()) {
            AskResponse response = service.ask(question.conversationId(), userId,
                    new AskRequest(question.question(), question.language()));
            requests++;
            if (response.mode() == AskMode.LLM) {
                llmCalls++;
                inputChars += response.usage().inputChars();
                baselineChars += corpus.baselineChars().get(question.conversationId());
            } else {
                zeroLlm++;
            }
            for (RagCitation citation : response.citations()) {
                allCited.add(citation.messageId());
                if (corpus.messageIds().get(question.conversationId()).contains(citation.messageId())) {
                    citedValid.add(citation.messageId());
                }
            }
        }

        double meanInput = llmCalls == 0 ? 0.0 : inputChars / llmCalls;
        double meanBaseline = llmCalls == 0 ? 0.0 : baselineChars / llmCalls;
        double reduction = meanBaseline == 0.0 ? 100.0 : (1.0 - meanInput / meanBaseline) * 100.0;
        return new RouterMetrics(
                (double) zeroLlm / requests,
                meanInput,
                meanBaseline,
                reduction,
                allCited.isEmpty() ? 1.0 : (double) citedValid.size() / allCited.size());
    }

    private record RetrievalMetrics(
            double recallAt3, double recallAt5, double mrr, double citationValidity) {
    }

    private record RouterMetrics(
            double zeroLlmRatio,
            double meanInputChars,
            double meanBaselineChars,
            double reductionPercent,
            double citationValidity) {
    }

    private record LookupQuestion(
            UUID conversationId, String question, SummaryLanguage language, UUID goldMessageId) {
    }

    private record BenchmarkQuestion(
            UUID conversationId, String question, SummaryLanguage language) {
    }

    private static final class SyntheticCorpus {
        private final InMemoryRagMessageIndex index = new InMemoryRagMessageIndex();
        private final List<LookupQuestion> lookupQuestions = new ArrayList<>();
        private final List<BenchmarkQuestion> questions = new ArrayList<>();
        private final Map<UUID, Set<UUID>> messageIds = new HashMap<>();
        private final Map<UUID, Integer> baselineChars = new HashMap<>();

        static SyntheticCorpus generate() {
            return new SyntheticCorpus().build();
        }

        private SyntheticCorpus build() {
            Random random = new Random(20261108L);
            for (int conversationIndex = 0; conversationIndex < CONVERSATION_COUNT; conversationIndex++) {
                UUID conversationId = UUID.nameUUIDFromBytes(("conv-" + conversationIndex).getBytes());
                UUID senderId = UUID.nameUUIDFromBytes(("sender-" + conversationIndex).getBytes());
                boolean vietnamese = conversationIndex % 2 == 0;
                Set<UUID> ids = new HashSet<>();
                int baseline = 0;
                int factsPlaced = 0;
                for (long seq = 1; seq <= MESSAGES_PER_CONVERSATION; seq++) {
                    boolean factPosition = seq % (MESSAGES_PER_CONVERSATION / FACTS_PER_CONVERSATION) == 0
                            && factsPlaced < FACTS_PER_CONVERSATION;
                    String content;
                    if (factPosition) {
                        int factIndex = factsPlaced % (vietnamese ? VI_TOPICS.length : EN_TOPICS.length);
                        String topic = vietnamese ? VI_TOPICS[factIndex] : EN_TOPICS[factIndex];
                        String value = vietnamese ? VI_VALUES[factIndex] : EN_VALUES[factIndex];
                        content = vietnamese ? topic + " là " + value + "." : "The " + topic + " is " + value + ".";
                        UUID factId = UUID.nameUUIDFromBytes(
                                ("fact-" + conversationIndex + "-" + seq).getBytes());
                        RagMessage fact = RagTestSupport.message(conversationId, factId, seq, senderId,
                                "Member", content);
                        index.add(fact);
                        ids.add(factId);
                        baseline += content.codePointCount(0, content.length());
                        String question = vietnamese
                                ? topic + " là gì?"
                                : "What is the " + topic + "?";
                        lookupQuestions.add(new LookupQuestion(conversationId, question,
                                vietnamese ? SummaryLanguage.VI : SummaryLanguage.EN, factId));
                        questions.add(new BenchmarkQuestion(conversationId, question,
                                vietnamese ? SummaryLanguage.VI : SummaryLanguage.EN));
                        factsPlaced++;
                    } else {
                        String[] fillers = vietnamese ? VI_FILLERS : EN_FILLERS;
                        content = fillers[random.nextInt(fillers.length)];
                        RagMessage filler = RagTestSupport.message(conversationId,
                                UUID.nameUUIDFromBytes(("msg-" + conversationIndex + "-" + seq).getBytes()),
                                seq, senderId, "Member", content);
                        index.add(filler);
                        ids.add(filler.id());
                        baseline += content.codePointCount(0, content.length());
                    }
                }
                for (int nonLookup = 0; nonLookup < 4; nonLookup++) {
                    String[] prompts = vietnamese ? VI_NON_LOOKUP : EN_NON_LOOKUP;
                    String question = prompts[nonLookup % prompts.length];
                    questions.add(new BenchmarkQuestion(conversationId, question,
                            vietnamese ? SummaryLanguage.VI : SummaryLanguage.EN));
                }
                messageIds.put(conversationId, ids);
                baselineChars.put(conversationId, baseline);
            }
            return this;
        }

        InMemoryRagMessageIndex index() {
            return index;
        }

        List<LookupQuestion> lookupQuestions() {
            return lookupQuestions;
        }

        List<BenchmarkQuestion> questions() {
            return questions;
        }

        Map<UUID, Set<UUID>> messageIds() {
            return messageIds;
        }

        Map<UUID, Integer> baselineChars() {
            return baselineChars;
        }
    }
}
