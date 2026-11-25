package com.nexachat.rag;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryConsentRepository.ConsentSnapshot;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.llm.Redactor;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final double MIN_AUGMENTED_RECALL_AT_5 = 0.80;
    private static final double MIN_AUGMENTED_RECALL_AT_3 = 0.70;
    private static final double MIN_AUGMENTED_MRR = 0.75;
    private static final double MIN_DISTRACTOR_NOT_FOUND = 0.95;

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
    private static final String[] EN_NEGATIVE_LOOKUP = {
            "What is the satellite launch deadline?",
            "Where is the cryptocurrency treasury report?",
            "When is the underwater robotics demonstration?",
            "Find the satellite launch deadline.",
            "Tell me where the cryptocurrency treasury report is.",
            "I need you to find the exact underwater robotics demonstration schedule that was mentioned "
                    + "in this conversation because I need it for the deployment."};
    private static final String[] VI_NEGATIVE_LOOKUP = {
            "Hạn chót phóng vệ tinh là khi nào?",
            "Kho lưu trữ tiền điện tử ở đâu?",
            "Buổi trình diễn robot dưới nước diễn ra khi nào?",
            "Tìm hạn chót phóng vệ tinh.",
            "Cho tôi biết kho lưu trữ tiền điện tử ở đâu.",
            "Tôi cần bạn tìm chính xác lịch trình diễn robot dưới nước đã được nhắc đến "
                    + "trong cuộc trò chuyện này vì tôi cần cho việc triển khai."};

    private static final String[] EN_DISTRACTOR_LOOKUP = {
            "What is the deployment budget?",
            "What is the satellite deployment window?",
            "When is the security hiring plan?"};
    private static final String[] VI_DISTRACTOR_LOOKUP = {
            "Ngân sách cho cuộc họp khởi động là gì?",
            "Lịch trình phóng vệ tinh là gì?",
            "Kế hoạch tuyển dụng bảo mật là gì?"};

    @Test
    void benchmarkMeetsRetrievalAndCostTargets() {
        SyntheticCorpus corpus = SyntheticCorpus.generate();
        RagRetrievalService retrieval = RagTestSupport.retrieval(corpus.index(), new RagProperties());

        RetrievalMetrics retrievalMetrics = evaluateRetrieval(corpus, retrieval);
        RetrievalMetrics augmentedMetrics = evaluateAugmentedRetrieval(corpus, retrieval);
        AtomicInteger llmInvocations = new AtomicInteger();
        AskService askService = newAskService(corpus, llmInvocations);
        RouterMetrics routerMetrics = evaluateRouter(corpus, askService);
        NegativeMetrics negativeMetrics = evaluateNegativeQuestions(corpus, askService, llmInvocations);
        NegativeMetrics distractorMetrics = evaluateDistractorQuestions(corpus, askService, llmInvocations);
        PositiveMetrics augmentedPositiveMetrics = evaluateAugmentedPositiveQuestions(corpus, askService);

        System.out.printf(
                "rag-eval: conversations=%d messages=%d lookupQuestions=%d "
                        + "recall@3=%.3f recall@5=%.3f mrr=%.3f citationValidity=%.3f "
                        + "zeroLlm=%.1f%% meanInputChars=%.1f baselineChars=%.1f reduction=%.1f%% "
                        + "negativeQuestions=%d negativeNotFound=%.3f negativeLlm=%d negativeCitations=%d "
                        + "augmentedQuestions=%d augmentedRecall@3=%.3f augmentedRecall@5=%.3f augmentedMrr=%.3f "
                        + "distractorQuestions=%d distractorNotFound=%.3f distractorLlm=%d distractorCitations=%d "
                        + "augmentedAnswered=%.3f augmentedCorrectCitations=%.3f augmentedNotFound=%d%n",
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
                routerMetrics.reductionPercent(),
                corpus.negativeQuestions().size(),
                negativeMetrics.notFoundRatio(),
                negativeMetrics.llmCalls(),
                negativeMetrics.citationCount(),
                corpus.augmentedLookupQuestions().size(),
                augmentedMetrics.recallAt3(),
                augmentedMetrics.recallAt5(),
                augmentedMetrics.mrr(),
                corpus.distractorQuestions().size(),
                distractorMetrics.notFoundRatio(),
                distractorMetrics.llmCalls(),
                distractorMetrics.citationCount(),
                augmentedPositiveMetrics.answeredRatio(),
                augmentedPositiveMetrics.correctCitationRatio(),
                augmentedPositiveMetrics.notFound());

        assertThat(corpus.lookupQuestions().size()).isGreaterThanOrEqualTo(150);
        assertThat(corpus.negativeQuestions().size()).isGreaterThanOrEqualTo(96);
        assertThat(retrievalMetrics.recallAt5()).isGreaterThanOrEqualTo(MIN_RECALL_AT_5);
        assertThat(retrievalMetrics.recallAt3()).isGreaterThanOrEqualTo(MIN_RECALL_AT_3);
        assertThat(retrievalMetrics.citationValidity()).isEqualTo(1.0);
        assertThat(routerMetrics.zeroLlmRatio()).isGreaterThanOrEqualTo(MIN_ZERO_LLM_RATIO);
        assertThat(routerMetrics.meanInputChars())
                .isLessThanOrEqualTo(routerMetrics.meanBaselineChars() * MAX_INPUT_CHAR_RATIO);
        assertThat(negativeMetrics.notFoundRatio()).isEqualTo(1.0);
        assertThat(negativeMetrics.llmCalls()).isZero();
        assertThat(negativeMetrics.citationCount()).isZero();
        assertThat(corpus.augmentedLookupQuestions().size()).isGreaterThanOrEqualTo(150);
        assertThat(augmentedMetrics.recallAt5()).isGreaterThanOrEqualTo(MIN_AUGMENTED_RECALL_AT_5);
        assertThat(augmentedMetrics.recallAt3()).isGreaterThanOrEqualTo(MIN_AUGMENTED_RECALL_AT_3);
        assertThat(augmentedMetrics.mrr()).isGreaterThanOrEqualTo(MIN_AUGMENTED_MRR);
        assertThat(augmentedMetrics.citationValidity()).isEqualTo(1.0);
        assertThat(corpus.distractorQuestions().size()).isGreaterThanOrEqualTo(96);
        assertThat(distractorMetrics.notFoundRatio()).isGreaterThanOrEqualTo(MIN_DISTRACTOR_NOT_FOUND);
        assertThat(distractorMetrics.llmCalls()).isZero();
        assertThat(distractorMetrics.citationCount()).isZero();
        assertThat(augmentedPositiveMetrics.answeredRatio()).isGreaterThanOrEqualTo(0.95);
        assertThat(augmentedPositiveMetrics.correctCitationRatio()).isGreaterThanOrEqualTo(0.90);
    }

    private RetrievalMetrics evaluateRetrieval(SyntheticCorpus corpus, RagRetrievalService retrieval) {
        return evaluateLookupQuestions(corpus.lookupQuestions(), corpus.messageIds(), retrieval);
    }

    private RetrievalMetrics evaluateAugmentedRetrieval(SyntheticCorpus corpus, RagRetrievalService retrieval) {
        return evaluateLookupQuestions(corpus.augmentedLookupQuestions(), corpus.messageIds(), retrieval);
    }

    private RetrievalMetrics evaluateLookupQuestions(List<LookupQuestion> questions,
            Map<UUID, Set<UUID>> messageIds, RagRetrievalService retrieval) {
        int hitsAt3 = 0;
        int hitsAt5 = 0;
        double reciprocalRank = 0.0;
        int validCitations = 0;
        int totalCitations = 0;
        for (LookupQuestion question : questions) {
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
            Set<UUID> conversationIds = messageIds.get(question.conversationId());
            for (RagContextItem item : result.context()) {
                for (UUID messageId : item.messageIds()) {
                    totalCitations++;
                    if (conversationIds.contains(messageId)) {
                        validCitations++;
                    }
                }
            }
        }
        int questionCount = questions.size();
        return new RetrievalMetrics(
                (double) hitsAt3 / questionCount,
                (double) hitsAt5 / questionCount,
                reciprocalRank / questionCount,
                totalCitations == 0 ? 1.0 : (double) validCitations / totalCitations);
    }

    private AskService newAskService(SyntheticCorpus corpus, AtomicInteger llmInvocations) {
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
        when(memberRepository.findJoinedAt(any(), any())).thenReturn(java.util.Optional.of(java.time.Instant.EPOCH));
        when(consentRepository.isConsented(any())).thenReturn(true);
        when(consentRepository.snapshot(any())).thenReturn(new ConsentSnapshot(true, 1));
        when(questionCache.citationsValid(any(), any())).thenReturn(true);
        when(questionCache.contextSnapshot(any())).thenReturn(new RagQuestionCache.ContextSnapshot(1, null));
        when(questionCache.contextUnchanged(any(), any())).thenReturn(true);
        when(llmAnswerService.answer(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            llmInvocations.incrementAndGet();
            List<RagContextItem> context = invocation.getArgument(4);
            if (context.isEmpty()) {
                return null;
            }
            String llmQuestion = invocation.getArgument(2);
            SummaryLanguage llmLanguage = invocation.getArgument(3);
            Map<UUID, RagMessage> messages = invocation.getArgument(5);
            Set<String> questionTerms = new HashSet<>(RagQuestionTerms.extract(llmQuestion, llmLanguage, 12));
            RagMessage bestMessage = null;
            int bestOverlap = 0;
            for (RagContextItem item : context) {
                for (UUID messageId : item.messageIds()) {
                    RagMessage message = messages.get(messageId);
                    if (message == null) {
                        continue;
                    }
                    Set<String> messageTerms = new HashSet<>(RagQuestionTerms.contentTerms(
                            message.content(), llmLanguage));
                    int overlap = 0;
                    for (String term : questionTerms) {
                        if (messageTerms.contains(term)) {
                            overlap++;
                        }
                    }
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap;
                        bestMessage = message;
                    }
                }
            }
            if (bestMessage == null) {
                return new RagLlmAnswer("Synthetic answer.", context.get(0).messageIds());
            }
            return new RagLlmAnswer("Synthetic answer.", List.of(bestMessage.id()));
        });

        return new AskService(
                rateLimiter, conversationRepository, memberRepository, consentRepository,
                embeddings, retrieval, questionCache, llmAnswerService, properties, stats,
                Clock.systemUTC());
    }

    private RouterMetrics evaluateRouter(SyntheticCorpus corpus, AskService service) {
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

    private NegativeMetrics evaluateNegativeQuestions(SyntheticCorpus corpus, AskService service,
            AtomicInteger llmInvocations) {
        UUID userId = UUID.randomUUID();
        int notFound = 0;
        int citationCount = 0;
        int llmCallsBefore = llmInvocations.get();
        for (BenchmarkQuestion question : corpus.negativeQuestions()) {
            AskResponse response = service.ask(question.conversationId(), userId,
                    new AskRequest(question.question(), question.language()));
            if (response.mode() == AskMode.NOT_FOUND) {
                notFound++;
            }
            citationCount += response.citations().size();
        }
        int total = corpus.negativeQuestions().size();
        return new NegativeMetrics((double) notFound / total,
                llmInvocations.get() - llmCallsBefore, citationCount);
    }

    private NegativeMetrics evaluateDistractorQuestions(SyntheticCorpus corpus, AskService service,
            AtomicInteger llmInvocations) {
        UUID userId = UUID.randomUUID();
        int notFound = 0;
        int citationCount = 0;
        int llmCallsBefore = llmInvocations.get();
        for (BenchmarkQuestion question : corpus.distractorQuestions()) {
            AskResponse response = service.ask(question.conversationId(), userId,
                    new AskRequest(question.question(), question.language()));
            if (response.mode() == AskMode.NOT_FOUND) {
                notFound++;
            }
            citationCount += response.citations().size();
        }
        int total = corpus.distractorQuestions().size();
        return new NegativeMetrics((double) notFound / total,
                llmInvocations.get() - llmCallsBefore, citationCount);
    }

    private PositiveMetrics evaluateAugmentedPositiveQuestions(SyntheticCorpus corpus, AskService service) {
        UUID userId = UUID.randomUUID();
        int answered = 0;
        int correctCitations = 0;
        int notFound = 0;
        for (LookupQuestion question : corpus.augmentedLookupQuestions()) {
            AskResponse response = service.ask(question.conversationId(), userId,
                    new AskRequest(question.question(), question.language()));
            if (response.mode() == AskMode.NOT_FOUND) {
                notFound++;
                continue;
            }
            answered++;
            boolean correct = response.citations().stream()
                    .anyMatch(citation -> citation.messageId().equals(question.goldMessageId()));
            if (correct) {
                correctCitations++;
            }
        }
        int total = corpus.augmentedLookupQuestions().size();
        return new PositiveMetrics((double) answered / total, (double) correctCitations / total, notFound);
    }

    private record PositiveMetrics(double answeredRatio, double correctCitationRatio, int notFound) {
    }

    private record NegativeMetrics(double notFoundRatio, int llmCalls, int citationCount) {
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
        private final List<LookupQuestion> augmentedLookupQuestions = new ArrayList<>();
        private final List<BenchmarkQuestion> questions = new ArrayList<>();
        private final List<BenchmarkQuestion> negativeQuestions = new ArrayList<>();
        private final List<BenchmarkQuestion> distractorQuestions = new ArrayList<>();
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
                        augmentedLookupQuestions.add(new LookupQuestion(conversationId,
                                augmentedQuestion(topic, vietnamese, factIndex),
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
                String[] negativePrompts = vietnamese ? VI_NEGATIVE_LOOKUP : EN_NEGATIVE_LOOKUP;
                for (String question : negativePrompts) {
                    negativeQuestions.add(new BenchmarkQuestion(conversationId, question,
                            vietnamese ? SummaryLanguage.VI : SummaryLanguage.EN));
                }
                String[] distractorPrompts = vietnamese ? VI_DISTRACTOR_LOOKUP : EN_DISTRACTOR_LOOKUP;
                for (String question : distractorPrompts) {
                    distractorQuestions.add(new BenchmarkQuestion(conversationId, question,
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

        List<BenchmarkQuestion> negativeQuestions() {
            return negativeQuestions;
        }

        List<LookupQuestion> augmentedLookupQuestions() {
            return augmentedLookupQuestions;
        }

        List<BenchmarkQuestion> distractorQuestions() {
            return distractorQuestions;
        }

        Map<UUID, Set<UUID>> messageIds() {
            return messageIds;
        }

        Map<UUID, Integer> baselineChars() {
            return baselineChars;
        }
    }

    private static String augmentedQuestion(String topic, boolean vietnamese, int factIndex) {
        int variant = factIndex % 6;
        if (vietnamese) {
            return switch (variant) {
                case 0 -> dropOneCharacter(topic, factIndex) + " là gì?";
                case 1 -> "Cho mình biết " + topic + ".";
                case 2 -> "Tìm " + topic + ".";
                case 3 -> stripAccents(topic + " là gì");
                case 4 -> "Giải thích " + topic + " giúp mình.";
                default -> "Mình cần chi tiết " + topic + ".";
            };
        }
        return switch (variant) {
            case 0 -> "What is the " + dropOneCharacter(topic, factIndex) + "?";
            case 1 -> "Tell me about the " + topic + ".";
            case 2 -> "Find the " + topic + ".";
            case 3 -> "what is " + topic;
            case 4 -> "Can you explain the " + topic + "?";
            default -> "I need the " + topic + " details.";
        };
    }

    private static String dropOneCharacter(String text, int salt) {
        if (text == null || text.length() < 2) {
            return text;
        }
        String[] tokens = text.split(" ");
        int longest = 0;
        for (int index = 1; index < tokens.length; index++) {
            if (tokens[index].length() > tokens[longest].length()) {
                longest = index;
            }
        }
        if (tokens[longest].length() < 5) {
            return text;
        }
        int position = Math.floorMod(salt * 5 + 2, tokens[longest].length());
        tokens[longest] = tokens[longest].substring(0, position) + tokens[longest].substring(position + 1);
        return String.join(" ", tokens);
    }

    private static String stripAccents(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.replace('đ', 'd').replace('Đ', 'D');
    }
}
