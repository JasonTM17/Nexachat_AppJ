package com.nexachat.rag;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.summary.ConversationAiOptOuts;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryConsentRepository.ConsentSnapshot;
import com.nexachat.summary.llm.Redactor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class RagRepresentativeEvaluation {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(START.plusSeconds(86_400), ZoneOffset.UTC);
    private static final List<String> FILLER = List.of(
            "I logged the daily cleanup and sent the photo.", "All good here, I will check in again tomorrow.",
            "Mình đã dọn bàn chung xong rồi.", "Cảm ơn bạn đã gửi ảnh lúc nãy.",
            "I updated our attendance sheet after lunch.", "Mình vừa đánh dấu mục đã xong trên bảng.");

    private RagRepresentativeEvaluation() {}

    static Report run(RagRepresentativeCorpus corpus) {
        InMemoryRagMessageIndex index = new InMemoryRagMessageIndex();
        Map<UUID, RagMessage> eligible = new HashMap<>();
        AtomicInteger providerRoutes = new AtomicInteger();
        UUID actor = id(corpus, "actor");
        Random random = new Random(corpus.seed());
        ConversationRepository conversations = mock(ConversationRepository.class);
        ConversationMemberRepository members = mock(ConversationMemberRepository.class);
        ConversationAiOptOuts optOuts = mock(ConversationAiOptOuts.class);
        for (RagRepresentativeCorpus.Conversation conversation : corpus.conversations()) {
            UUID conversationId = id(corpus, "conversation/" + conversation.id());
            when(conversations.existsById(conversationId)).thenReturn(true);
            when(members.findJoinedAt(conversationId, actor)).thenReturn(Optional.of(START));
            addConversation(corpus, conversation, conversationId, random, index, eligible);
        }
        when(optOuts.hasOptedOutMessages(any())).thenAnswer(call ->
                index.hasOptedOutMessages(call.getArgument(0), START));

        RagProperties properties = new RagProperties();
        RagStats stats = new RagStats();
        RagEmbeddingService embeddings = new RagEmbeddingService(new RagEmbeddingProviders(properties),
                index, new VectorCache(1024), properties, stats, new Redactor());
        RagRetrievalService retrieval = new RagRetrievalService(index, embeddings, properties);
        SummaryConsentRepository consent = mock(SummaryConsentRepository.class);
        when(consent.snapshot(actor)).thenReturn(new ConsentSnapshot(true, 1));
        RagQuestionCache cache = mock(RagQuestionCache.class);
        when(cache.contextSnapshot(any())).thenReturn(new RagQuestionCache.ContextSnapshot(1, null));
        when(cache.contextUnchanged(any(), any())).thenReturn(true);
        RagLlmAnswerService provider = mock(RagLlmAnswerService.class);
        when(provider.answerDetailed(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    providerRoutes.incrementAndGet();
                    return RagLlmOutcome.providerError(true);
                });
        AskService ask = new AskService(mock(AskRateLimiter.class), conversations, members, consent,
                embeddings, retrieval, cache, provider, properties, stats, optOuts, CLOCK);

        List<CaseResult> results = new ArrayList<>();
        for (RagRepresentativeCorpus.Question question : corpus.questions()) {
            UUID conversationId = id(corpus, "conversation/" + question.conversation());
            List<UUID> gold = question.gold().stream().map(source -> id(corpus, "message/" + source)).toList();
            int routesBefore = providerRoutes.get();
            AskResponse response = ask.ask(conversationId, actor, new AskRequest(question.text(), question.language()));
            RetrievalResult answerEligible = retrieval.retrieve(conversationId, actor, question.text(), question.language(),
                    true, START, true);
            // Measure ranking before answerability filtering, as the existing benchmark does.
            RetrievalResult ranked = retrieval.retrieve(conversationId, actor, question.text(), question.language(),
                    true, START, false);
            results.add(score(question, gold, response, ranked, answerEligible, eligible, conversationId,
                    providerRoutes.get() - routesBefore));
        }
        return new Report(corpus.split(), corpus.seed(), List.copyOf(results));
    }

    private static void addConversation(RagRepresentativeCorpus corpus, RagRepresentativeCorpus.Conversation fixture,
                                        UUID conversationId, Random random, InMemoryRagMessageIndex index,
                                        Map<UUID, RagMessage> eligible) {
        long seq = 0;
        for (RagRepresentativeCorpus.Message message : fixture.messages()) {
            seq++;
            Instant created = START.plusSeconds(seq);
            RagMessage row = new RagMessage(id(corpus, "message/" + message.id()), conversationId, seq,
                    id(corpus, fixture.id() + "/speaker/" + message.speaker()), message.speaker(), message.text(),
                    created, message.edited() ? created.plusSeconds(1) : null, null);
            index.add(row);
            if (message.optedOut()) {
                index.optOutMessage(row.id());
            } else {
                eligible.put(row.id(), row);
            }
        }
        UUID fillerSpeaker = id(corpus, fixture.id() + "/filler-speaker");
        for (int position = 0; position < fixture.fillerCount(); position++) {
            seq++;
            String ref = fixture.id() + "/filler/" + position;
            String text = FILLER.get(random.nextInt(FILLER.size())) + " [" + ref + "]";
            RagMessage row = new RagMessage(id(corpus, ref), conversationId, seq, fillerSpeaker,
                    "Fixture member", text, START.plusSeconds(seq), null, null);
            index.add(row);
            eligible.put(row.id(), row);
        }
    }

    private static CaseResult score(RagRepresentativeCorpus.Question question, List<UUID> gold,
                                    AskResponse response, RetrievalResult ranked, RetrievalResult answerEligible,
                                    Map<UUID, RagMessage> eligible, UUID conversationId, int providerRoutes) {
        Stream<UUID> responseIds = response.citations().stream().map(RagCitation::messageId);
        boolean scopeViolation = Stream.concat(Stream.concat(retrievalIds(ranked), retrievalIds(answerEligible)),
                        responseIds)
                .anyMatch(id -> !inScope(eligible.get(id), conversationId));
        long validCitations = response.citations().stream().filter(citation -> {
            RagMessage source = eligible.get(citation.messageId());
            return inScope(source, conversationId) && source.seq() == citation.seq();
        }).count();
        boolean goldCited = response.citations().stream().anyMatch(c -> gold.contains(c.messageId()));
        boolean supportedAnswer = goldCited && response.answer() != null && question.answerable()
                && response.answer().toLowerCase(Locale.ROOT).contains(question.answerContains().toLowerCase(Locale.ROOT));
        boolean abstained = response.mode() == AskMode.NOT_FOUND && response.answer() == null
                && response.citations().isEmpty() && providerRoutes == 0
                && (question.category() != RagRepresentativeCorpus.Category.OPTED_OUT || response.contextOmitted());
        return new CaseResult(question, rankOfGold(ranked, gold), rankOfGold(answerEligible, gold), response.mode(),
                response.notFoundReason(), supportedAnswer, abstained, providerRoutes, scopeViolation,
                response.citations().size(), validCitations);
    }

    private static int rankOfGold(RetrievalResult retrieval, List<UUID> gold) {
        List<UUID> ids = retrieval.ranked().stream().map(item -> item.message().id()).toList();
        for (int i = 0; i < ids.size(); i++) {
            if (gold.contains(ids.get(i))) return i + 1;
        }
        return 0;
    }

    private static Stream<UUID> retrievalIds(RetrievalResult result) {
        return Stream.concat(result.ranked().stream().map(item -> item.message().id()),
                result.context().stream().flatMap(item -> item.messageIds().stream()));
    }

    private static boolean inScope(RagMessage message, UUID conversationId) {
        return message != null && message.conversationId().equals(conversationId);
    }

    private static UUID id(RagRepresentativeCorpus corpus, String key) {
        return UUID.nameUUIDFromBytes((corpus.version() + "/" + corpus.seed() + "/" + key)
                .getBytes(StandardCharsets.UTF_8));
    }

    record CaseResult(RagRepresentativeCorpus.Question question, int rank, int answerEligibleRank,
                      AskMode mode, AskReason reason,
                      boolean supportedAnswer, boolean abstained, int providerRoutes, boolean scopeViolation,
                      int citations, long validCitations) {}

    record Report(String split, long seed, List<CaseResult> cases) {
        List<CaseResult> positives() {
            return cases.stream().filter(result -> result.question().answerable()).toList();
        }

        List<CaseResult> negatives() {
            return cases.stream().filter(result -> !result.question().answerable()).toList();
        }

        double recallAt(int k) {
            List<CaseResult> positives = positives();
            return (double) positives.stream().filter(result -> result.rank() > 0 && result.rank() <= k).count()
                    / positives.size();
        }

        double reciprocalRank() {
            return positives().stream().mapToDouble(result -> result.rank() == 0 ? 0 : 1.0 / result.rank())
                    .average().orElseThrow();
        }

        int citationCount() {
            return cases.stream().mapToInt(CaseResult::citations).sum();
        }

        double citationValidity() {
            return citationCount() == 0 ? 0 : (double) cases.stream().mapToLong(CaseResult::validCitations).sum()
                    / citationCount();
        }

        double zeroLlm() {
            return (double) cases.stream().filter(result -> result.providerRoutes() == 0).count() / cases.size();
        }

        void print() {
            for (CaseResult result : cases) {
                System.out.printf(Locale.ROOT,
                        "rag-slice-case: split=%s id=%s language=%s category=%s rank=%d answerEligibleRank=%d mode=%s reason=%s "
                                + "supported=%b abstained=%b providerRoutes=%d scopeViolation=%b%n",
                        split, result.question().id(), result.question().language(), result.question().category(),
                        result.rank(), result.answerEligibleRank(), result.mode(), result.reason(),
                        result.supportedAnswer(), result.abstained(),
                        result.providerRoutes(), result.scopeViolation());
            }
            System.out.printf(Locale.ROOT,
                    "rag-slice: version=1 split=%s seed=%d profile=lexical-in-memory positives=%d negatives=%d "
                            + "recall@3=%.3f recall@5=%.3f mrr=%.3f citationValidity=%.3f citations=%d "
                            + "answerEligibleHitsAt5=%d supportedAnswers=%d abstained=%d zeroLlm=%.3f scopeViolations=%d%n",
                    split, seed, positives().size(), negatives().size(), recallAt(3), recallAt(5), reciprocalRank(),
                    citationValidity(), citationCount(),
                    positives().stream().filter(result -> result.answerEligibleRank() > 0
                            && result.answerEligibleRank() <= 5).count(),
                    positives().stream().filter(CaseResult::supportedAnswer).count(),
                    negatives().stream().filter(CaseResult::abstained).count(), zeroLlm(),
                    cases.stream().filter(CaseResult::scopeViolation).count());
        }
    }
}
