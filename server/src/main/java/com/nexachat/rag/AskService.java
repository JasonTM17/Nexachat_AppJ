package com.nexachat.rag;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.summary.ConversationAiOptOuts;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.engine.CueDetector;
import com.nexachat.summary.engine.LanguageDetector;
import com.nexachat.summary.engine.SummaryMessage;
import com.nexachat.summary.engine.SummaryWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The ask router: cache, answerability gate, extractive answer, then the LLM
 * tier only for the retrieved, budgeted context. Every step records its mode
 * and counters; the endpoint never returns 503 for a provider failure.
 */
@Service
@Profile("!test")
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);
    private static final int LOOKUP_QUESTION_MAX_CHARS = 120;
    private static final int EXTRACTIVE_ANSWER_MAX_CHARS = 1_000;

    private final AskRateLimiter rateLimiter;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final SummaryConsentRepository consentRepository;
    private final RagEmbeddingService embeddingService;
    private final RagRetrievalService retrievalService;
    private final RagQuestionCache questionCache;
    private final RagLlmAnswerService llmAnswerService;
    private final RagProperties properties;
    private final RagStats stats;
    private final ConversationAiOptOuts aiOptOuts;
    private final Clock clock;

    public AskService(
            AskRateLimiter rateLimiter,
            ConversationRepository conversationRepository,
            ConversationMemberRepository memberRepository,
            SummaryConsentRepository consentRepository,
            RagEmbeddingService embeddingService,
            RagRetrievalService retrievalService,
            RagQuestionCache questionCache,
            RagLlmAnswerService llmAnswerService,
            RagProperties properties,
            RagStats stats,
            Clock clock) {
        this(rateLimiter, conversationRepository, memberRepository, consentRepository,
                embeddingService, retrievalService, questionCache, llmAnswerService,
                properties, stats, ConversationAiOptOuts.none(), clock);
    }

    @Autowired
    public AskService(
            AskRateLimiter rateLimiter,
            ConversationRepository conversationRepository,
            ConversationMemberRepository memberRepository,
            SummaryConsentRepository consentRepository,
            RagEmbeddingService embeddingService,
            RagRetrievalService retrievalService,
            RagQuestionCache questionCache,
            RagLlmAnswerService llmAnswerService,
            RagProperties properties,
            RagStats stats,
            ConversationAiOptOuts aiOptOuts,
            Clock clock) {
        this.rateLimiter = rateLimiter;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.consentRepository = consentRepository;
        this.embeddingService = embeddingService;
        this.retrievalService = retrievalService;
        this.questionCache = questionCache;
        this.llmAnswerService = llmAnswerService;
        this.properties = properties;
        this.stats = stats;
        this.aiOptOuts = aiOptOuts;
        this.clock = clock;
    }

    public AskResponse ask(UUID conversationId, UUID userId, AskRequest request) {
        stats.recordRequest();
        rateLimiter.consume(userId);
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        if (!memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Conversation membership required");
        }
        boolean contextOmitted = aiOptOuts.hasOptedOutMessages(conversationId);
        if (!properties.isEnabled()) {
            stats.recordNotFound();
            return AskResponse.notFound(contextOmitted);
        }

        boolean consented = consentRepository.isConsented(userId);
        String question = request.question().strip();
        SummaryLanguage language = request.language() == null || request.language() == SummaryLanguage.AUTO
                ? detectQuestionLanguage(question)
                : request.language();
        String questionHash = RagHashes.questionHash(RagQuestionNormalizer.normalize(question));

        RagEmbeddingService.EmbeddingOutcome questionEmbedding =
                embeddingService.embedQuestion(question, consented);

        Optional<RagQuestionCache.CachedAsk> exact = questionCache.findExact(
                conversationId, questionEmbedding.model(), questionHash);
        if (exact.isPresent()
                && questionCache.citationsValid(exact.get().citedIds(), exact.get().citedHash())) {
            stats.recordCacheHit();
            return cachedResponse(exact.get(), contextOmitted);
        }

        double semanticThreshold = properties.getRetrieval().getSemanticCacheThreshold();
        for (RagQuestionCache.CachedQuestion candidate : questionCache.findCandidates(
                conversationId, questionEmbedding.model())) {
            if (VectorCodec.cosine(questionEmbedding.vector(), candidate.vector()) >= semanticThreshold
                    && questionCache.citationsValid(candidate.ask().citedIds(), candidate.ask().citedHash())) {
                stats.recordCacheHit();
                return cachedResponse(candidate.ask(), contextOmitted);
            }
        }

        RetrievalResult retrieval = retrievalService.retrieve(conversationId, question, language, consented);
        if (retrieval.isEmpty()
                || retrieval.bestScore() < properties.getRetrieval().getNotFoundThreshold()) {
            stats.recordNotFound();
            return AskResponse.notFound(contextOmitted);
        }

        if (isLookupLike(question)
                && retrieval.bestScore() >= properties.getRetrieval().getExtractiveThreshold()) {
            return answerExtractive(conversationId, questionEmbedding, questionHash, language, retrieval,
                    contextOmitted);
        }

        if (consented) {
            RagLlmAnswer llmAnswer = llmAnswerService.answer(
                    userId, conversationId, question, language, retrieval.context(), retrieval.messages());
            if (llmAnswer != null) {
                RagQuestionCache.CachedAsk answer = toCachedAsk(
                        llmAnswer, retrieval.messages(), AskMode.LLM, language);
                if (answer != null) {
                    saveAnswer(conversationId, questionEmbedding, questionHash, answer);
                    stats.recordLlmCall(retrieval.inputChars(), "fallback".equals(llmAnswer.provider()));
                    return toResponse(answer, AskMode.LLM, 1, retrieval.inputChars(), false,
                            llmAnswer.provider(), contextOmitted);
                }
            }
        }

        if (retrieval.bestScore() >= properties.getRetrieval().getExtractiveThreshold()) {
            return answerExtractive(conversationId, questionEmbedding, questionHash, language, retrieval,
                    contextOmitted);
        }
        stats.recordNotFound();
        return AskResponse.notFound(contextOmitted);
    }

    private AskResponse answerExtractive(
            UUID conversationId,
            RagEmbeddingService.EmbeddingOutcome questionEmbedding,
            String questionHash,
            SummaryLanguage language,
            RetrievalResult retrieval,
            boolean contextOmitted) {
        RagQuestionCache.CachedAsk answer = toCachedAsk(retrieval.topMessage(), retrieval.messages(), AskMode.EXTRACTIVE, language);
        if (answer == null) {
            stats.recordNotFound();
            return AskResponse.notFound(contextOmitted);
        }
        saveAnswer(conversationId, questionEmbedding, questionHash, answer);
        stats.recordExtractive();
        return toResponse(answer, AskMode.EXTRACTIVE, 0, 0, false, null, contextOmitted);
    }

    private void saveAnswer(
            UUID conversationId,
            RagEmbeddingService.EmbeddingOutcome questionEmbedding,
            String questionHash,
            RagQuestionCache.CachedAsk answer) {
        try {
            questionCache.save(conversationId, questionEmbedding.model(), questionHash,
                    questionEmbedding.vector(), answer);
        } catch (RuntimeException exception) {
            // A cache write failure must never fail the answer.
            log.debug("ask cache write failed: {}", exception.getClass().getSimpleName());
        }
    }

    private RagQuestionCache.CachedAsk toCachedAsk(
            RagMessage message, Map<UUID, RagMessage> messages, AskMode mode, SummaryLanguage language) {
        if (message == null) {
            return null;
        }
        RagCitation citation = new RagCitation(message.id(), message.seq(), snippet(message.content()));
        return new RagQuestionCache.CachedAsk(
                truncateByCodePoints(message.content(), EXTRACTIVE_ANSWER_MAX_CHARS),
                List.of(citation),
                mode.name(),
                List.of(message.id()),
                RagHashes.citationsHash(List.of(message)),
                language);
    }

    private RagQuestionCache.CachedAsk toCachedAsk(
            RagLlmAnswer llmAnswer, Map<UUID, RagMessage> messages, AskMode mode, SummaryLanguage language) {
        List<RagCitation> citations = new ArrayList<>();
        List<UUID> citedIds = new ArrayList<>();
        List<RagMessage> citedMessages = new ArrayList<>();
        for (UUID citationId : llmAnswer.citationIds()) {
            RagMessage message = messages.get(citationId);
            if (message == null || citedIds.contains(citationId)) {
                continue;
            }
            citations.add(new RagCitation(citationId, message.seq(), snippet(message.content())));
            citedIds.add(citationId);
            citedMessages.add(message);
        }
        if (citations.isEmpty()) {
            return null;
        }
        return new RagQuestionCache.CachedAsk(
                llmAnswer.answer(),
                citations,
                mode.name(),
                citedIds,
                RagHashes.citationsHash(citedMessages),
                language);
    }

    private AskResponse cachedResponse(RagQuestionCache.CachedAsk answer, boolean contextOmitted) {
        return new AskResponse(answer.answer(), answer.citations(), AskMode.CACHE, contextOmitted,
                new AskResponse.Usage(0, 0, true));
    }

    private AskResponse toResponse(
            RagQuestionCache.CachedAsk answer,
            AskMode mode,
            int llmCalls,
            int inputChars,
            boolean cached,
            String provider,
            boolean contextOmitted) {
        return new AskResponse(answer.answer(), answer.citations(), mode, contextOmitted,
                new AskResponse.Usage(llmCalls, inputChars, cached, provider));
    }

    private boolean isLookupLike(String question) {
        if (question.codePointCount(0, question.length()) > LOOKUP_QUESTION_MAX_CHARS) {
            return false;
        }
        return CueDetector.isQuestion(new SummaryMessage("probe", 0, "probe", "", question));
    }

    private SummaryLanguage detectQuestionLanguage(String question) {
        com.nexachat.summary.engine.SummaryLanguage detected = LanguageDetector.detect(
                new SummaryWindow(List.of(new SummaryMessage("question", 0, "user", "", question))));
        return SummaryLanguage.valueOf(detected.name());
    }

    private String snippet(String content) {
        String folded = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return truncateByCodePoints(folded, properties.getRetrieval().getMaxSnippetChars());
    }

    private static String truncateByCodePoints(String text, int maxCodePoints) {
        if (text == null) {
            return "";
        }
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }
}
