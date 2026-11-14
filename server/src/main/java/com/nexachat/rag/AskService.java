package com.nexachat.rag;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
    /**
     * One cold-cache computation per identical ask. Followers wait for the
     * leader's response instead of repeating the embedding, retrieval and
     * provider work. Conversation and user identify the membership scope, and
     * the consent snapshot prevents a request from joining work started under
     * a different consent epoch.
     */
    private final ConcurrentHashMap<AskKey, CompletableFuture<AskResponse>> inFlight =
            new ConcurrentHashMap<>();

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
        AskExecution execution = askForResponse(conversationId, userId, request);
        if (!execution.consentSnapshot().equals(consentRepository.snapshot(userId))) {
            stats.recordNotFound();
            return AskResponse.notFound(execution.response().contextOmitted());
        }
        return execution.response();
    }

    AskExecution askForResponse(UUID conversationId, UUID userId, AskRequest request) {
        stats.recordRequest();
        rateLimiter.consume(userId);
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        java.time.Instant admittedFrom = memberRepository.findJoinedAt(conversationId, userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_MEMBER, "Conversation membership required"));
        // Membership gates the e2ee flag so a non-member cannot probe it.
        boolean e2ee = conversationRepository.findById(conversationId)
                .map(Conversation::isE2eeEnabled).orElse(false);
        if (e2ee) {
            throw ApiException.of(ApiErrorCode.E2EE_AI_UNAVAILABLE,
                    "AI features are unavailable in encrypted conversations");
        }
        boolean contextOmitted = aiOptOuts.hasOptedOutMessages(conversationId);
        SummaryConsentRepository.ConsentSnapshot consentSnapshot = consentRepository.snapshot(userId);
        if (!properties.isEnabled()) {
            stats.recordNotFound();
            return new AskExecution(consentSnapshot, AskResponse.notFound(contextOmitted));
        }

        String question = request.question().strip();
        SummaryLanguage language = request.language() == null || request.language() == SummaryLanguage.AUTO
                ? detectQuestionLanguage(question)
                : request.language();
        String questionHash = RagHashes.questionHash(RagQuestionNormalizer.normalize(question));

        AskKey key = new AskKey(conversationId, userId, questionHash, language, consentSnapshot);
        AskResponse response = computeOnce(key, () -> compute(
                conversationId, userId, question, language, questionHash, consentSnapshot, contextOmitted,
                admittedFrom));
        return new AskExecution(consentSnapshot, response);
    }

    /**
     * Single-flight: the first caller for a key computes and every concurrent
     * caller for the same key receives that result. The entry is removed in
     * {@code finally}, so a failure is never cached and the next request
     * recomputes. Callers that arrive after the leader removed the entry fall
     * through to the exact cache, which the leader has already written.
     */
    private AskResponse computeOnce(AskKey key, Supplier<AskResponse> computation) {
        CompletableFuture<AskResponse> leader = new CompletableFuture<>();
        CompletableFuture<AskResponse> pending = inFlight.putIfAbsent(key, leader);
        if (pending != null) {
            return await(pending);
        }
        try {
            AskResponse response = computation.get();
            leader.complete(response);
            return response;
        } catch (RuntimeException | Error failure) {
            leader.completeExceptionally(failure);
            throw failure;
        } finally {
            leader.completeExceptionally(new IllegalStateException("ask computation did not complete"));
            inFlight.remove(key, leader);
        }
    }

    private AskResponse await(CompletableFuture<AskResponse> pending) {
        try {
            return pending.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }

    private AskResponse compute(
            UUID conversationId,
            UUID userId,
            String question,
            SummaryLanguage language,
            String questionHash,
            SummaryConsentRepository.ConsentSnapshot consentSnapshot,
            boolean contextOmitted,
            java.time.Instant admittedFrom) {
        boolean consented = consentSnapshot.consented();
        // Cached answers are shared per conversation and may cite or paraphrase content from
        // before this member's admission, so a late or returning member always recomputes.
        boolean sharedCacheVisible = !retrievalService.hasContentBeforeAdmission(conversationId, admittedFrom);
        RagEmbeddingService.EmbeddingOutcome questionEmbedding =
                embeddingService.embedQuestion(question, consented);

        if (consented && sharedCacheVisible) {
            Optional<RagQuestionCache.CachedAsk> exact = questionCache.findExact(
                    conversationId, questionEmbedding.model(), questionHash);
            if (exact.isPresent()
                    && exact.get().language() == language
                    && questionCache.citationsValid(exact.get().citedIds(), exact.get().citedHash())) {
                stats.recordCacheHit();
                return cachedResponse(exact.get(), contextOmitted);
            }

            double semanticThreshold = properties.getRetrieval().getSemanticCacheThreshold();
            for (RagQuestionCache.CachedQuestion candidate : questionCache.findCandidates(
                    conversationId, questionEmbedding.model())) {
                if (VectorCodec.cosine(questionEmbedding.vector(), candidate.vector()) >= semanticThreshold
                        && candidate.ask().language() == language
                        && questionCache.citationsValid(candidate.ask().citedIds(), candidate.ask().citedHash())) {
                    stats.recordCacheHit();
                    return cachedResponse(candidate.ask(), contextOmitted);
                }
            }
        }

        long contentVersion = questionCache.contentVersion(conversationId);
        RetrievalResult retrieval = retrievalService.retrieve(
                conversationId, question, language, consented, admittedFrom);
        if (retrieval.isEmpty()
                || retrieval.bestScore() < properties.getRetrieval().getNotFoundThreshold()) {
            stats.recordNotFound();
            return AskResponse.notFound(contextOmitted);
        }

        if (isLookupLike(question)
                && retrieval.bestScore() >= properties.getRetrieval().getExtractiveThreshold()) {
            return answerExtractive(conversationId, questionEmbedding, questionHash, language, retrieval,
                    userId, consentSnapshot, contextOmitted, contentVersion);
        }

        if (consented) {
            RagLlmAnswer llmAnswer = llmAnswerService.answer(
                    userId, conversationId, question, language, retrieval.context(), retrieval.messages());
            if (llmAnswer != null) {
                RagQuestionCache.CachedAsk answer = toCachedAsk(
                        llmAnswer, retrieval.messages(), AskMode.LLM, language, contentVersion);
                if (answer != null) {
                    saveAnswer(userId, consentSnapshot, conversationId, questionEmbedding, questionHash, answer);
                    stats.recordLlmCall(retrieval.inputChars(), "fallback".equals(llmAnswer.provider()));
                    return toResponse(answer, AskMode.LLM, 1, retrieval.inputChars(), false,
                            llmAnswer.provider(), contextOmitted);
                }
            }
        }

        if (retrieval.bestScore() >= properties.getRetrieval().getExtractiveThreshold()) {
            return answerExtractive(conversationId, questionEmbedding, questionHash, language, retrieval,
                    userId, consentSnapshot, contextOmitted, contentVersion);
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
            UUID userId,
            SummaryConsentRepository.ConsentSnapshot consentSnapshot,
            boolean contextOmitted,
            long contentVersion) {
        RagQuestionCache.CachedAsk answer = toCachedAsk(
                retrieval.topMessage(), retrieval.messages(), AskMode.EXTRACTIVE, language, contentVersion);
        if (answer == null) {
            stats.recordNotFound();
            return AskResponse.notFound(contextOmitted);
        }
        saveAnswer(userId, consentSnapshot, conversationId, questionEmbedding, questionHash, answer);
        stats.recordExtractive();
        return toResponse(answer, AskMode.EXTRACTIVE, 0, 0, false, null, contextOmitted);
    }

    private void saveAnswer(
            UUID userId,
            SummaryConsentRepository.ConsentSnapshot consentSnapshot,
            UUID conversationId,
            RagEmbeddingService.EmbeddingOutcome questionEmbedding,
            String questionHash,
            RagQuestionCache.CachedAsk answer) {
        try {
            questionCache.saveIfConsentSnapshotMatches(userId, consentSnapshot, conversationId,
                    questionEmbedding.model(), questionHash, questionEmbedding.vector(), answer);
        } catch (RuntimeException exception) {
            // A cache write failure must never fail the answer.
            log.debug("ask cache write failed: {}", exception.getClass().getSimpleName());
        }
    }

    private RagQuestionCache.CachedAsk toCachedAsk(
            RagMessage message,
            Map<UUID, RagMessage> messages,
            AskMode mode,
            SummaryLanguage language,
            long contentVersion) {
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
                language,
                contentVersion);
    }

    private RagQuestionCache.CachedAsk toCachedAsk(
            RagLlmAnswer llmAnswer,
            Map<UUID, RagMessage> messages,
            AskMode mode,
            SummaryLanguage language,
            long contentVersion) {
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
                language,
                contentVersion);
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

    private record AskKey(
            UUID conversationId,
            UUID userId,
            String questionHash,
            SummaryLanguage language,
            SummaryConsentRepository.ConsentSnapshot consentSnapshot) {
    }
}
