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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
        // Capture before the E2EE gate: activation during capture must be seen by the gate;
        // activation afterward changes this revision and suppresses response selection.
        RagQuestionCache.ContextSnapshot context = questionCache.contextSnapshot(conversationId);
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
        List<AskPriorTurn> priorTurns = request.priorTurns();
        SummaryLanguage language = request.language() == null || request.language() == SummaryLanguage.AUTO
                ? detectQuestionLanguage(question)
                : request.language();
        String questionHash = RagHashes.questionHash(hashInput(question, priorTurns));

        AskKey key = new AskKey(conversationId, userId, questionHash, language, consentSnapshot,
                admittedFrom, context);
        AskResponse response = computeOnce(key, contextOmitted, () -> compute(
                conversationId, userId, question, language, questionHash, consentSnapshot, contextOmitted,
                admittedFrom, priorTurns, context));
        // Followers select their own response only while the context they joined is still current.
        if (!questionCache.contextUnchanged(conversationId, context)) {
            stats.recordNotFound();
            response = AskResponse.notFound(aiOptOuts.hasOptedOutMessages(conversationId));
        }
        return new AskExecution(consentSnapshot, response, conversationId, context);
    }

    /**
     * Single-flight: the first caller for a key computes and every concurrent
     * caller for the same key receives that result. The entry is removed in
     * {@code finally}, so a failure is never cached and the next request
     * recomputes. Callers that arrive after the leader removed the entry fall
     * through to the exact cache, which the leader has already written.
     */
    private AskResponse computeOnce(AskKey key, boolean contextOmitted, Supplier<AskResponse> computation) {
        CompletableFuture<AskResponse> leader = new CompletableFuture<>();
        CompletableFuture<AskResponse> pending = inFlight.putIfAbsent(key, leader);
        if (pending != null) {
            return await(key, pending, contextOmitted);
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

    private AskResponse await(AskKey key, CompletableFuture<AskResponse> pending, boolean contextOmitted) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getAsk().getSingleFlightWaitMs());
        try {
            return pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            // Detach only the observed stalled leader. Its late finally must not remove a replacement.
            inFlight.remove(key, pending);
            stats.recordNotFound();
            return AskResponse.notFound(contextOmitted);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Interrupted while waiting for an AI answer");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("AI answer computation failed", cause);
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
            java.time.Instant admittedFrom,
            List<AskPriorTurn> priorTurns,
            RagQuestionCache.ContextSnapshot context) {
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
                    && exact.get().expectedContentVersion() == context.revision()
                    && exact.get().language() == language
                    && questionCache.citationsValid(exact.get().citedIds(), exact.get().citedHash())) {
                stats.recordCacheHit();
                return cachedResponse(exact.get(), contextOmitted);
            }

            double semanticThreshold = properties.getRetrieval().getSemanticCacheThreshold();
            // Follow-up questions like "what about Tuesday?" must not replay an
            // unrelated cached answer for a similar current-question embedding.
            if (priorTurns.isEmpty()) {
                for (RagQuestionCache.CachedQuestion candidate : questionCache.findCandidates(
                        conversationId, questionEmbedding.model())) {
                    if (VectorCodec.cosine(questionEmbedding.vector(), candidate.vector()) >= semanticThreshold
                            && candidate.ask().expectedContentVersion() == context.revision()
                            && candidate.ask().language() == language
                            && questionCache.citationsValid(candidate.ask().citedIds(), candidate.ask().citedHash())) {
                        stats.recordCacheHit();
                        return cachedResponse(candidate.ask(), contextOmitted);
                    }
                }
            }
        }

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
                    userId, consentSnapshot, contextOmitted, context);
        }

        if (consented) {
            RagLlmAnswer llmAnswer = llmAnswerService.answer(
                    userId, conversationId, question, language, retrieval.context(), retrieval.messages(),
                    priorTurns);
            if (llmAnswer != null) {
                RagQuestionCache.CachedAsk answer = toCachedAsk(
                        llmAnswer, retrieval.messages(), AskMode.LLM, language, context);
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
                    userId, consentSnapshot, contextOmitted, context);
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
            RagQuestionCache.ContextSnapshot context) {
        RagQuestionCache.CachedAsk answer = toCachedAsk(
                retrieval.topMessage(), retrieval.messages(), AskMode.EXTRACTIVE, language, context);
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
            RagQuestionCache.ContextSnapshot context) {
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
                context.revision(), context.expiresAt());
    }

    private RagQuestionCache.CachedAsk toCachedAsk(
            RagLlmAnswer llmAnswer,
            Map<UUID, RagMessage> messages,
            AskMode mode,
            SummaryLanguage language,
            RagQuestionCache.ContextSnapshot context) {
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
                context.revision(), context.expiresAt());
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

    static String hashInput(String question, List<AskPriorTurn> priorTurns) {
        String normalized = RagQuestionNormalizer.normalize(question);
        String fingerprint = AskPriorTurns.fingerprint(priorTurns);
        if (fingerprint.isEmpty()) {
            return normalized;
        }
        return normalized + "\nprior:" + fingerprint;
    }

    private record AskKey(
            UUID conversationId,
            UUID userId,
            String questionHash,
            SummaryLanguage language,
            SummaryConsentRepository.ConsentSnapshot consentSnapshot,
            Instant admittedFrom,
            RagQuestionCache.ContextSnapshot context) {
    }
}
