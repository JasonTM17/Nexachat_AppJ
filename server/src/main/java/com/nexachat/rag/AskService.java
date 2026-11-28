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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ask router: cache, answerability gate, precise extractive answer, then the
 * typed LLM tier only for the retrieved, budgeted context. Provider abstentions
 * are evidence-based NOT_FOUND results; only independent high-confidence
 * factoid extraction may degrade a provider failure to EXTRACTIVE.
 */
@Service
@Profile("!test")
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);
    private static final int LOOKUP_QUESTION_MAX_CHARS = 120;
    private static final int EXTRACTIVE_ANSWER_MAX_CHARS = 1_000;
    private static final int RETRY_AFTER_TRANSIENT_SECONDS = 1;

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
    /** One cold-cache computation per identical ask and visibility snapshot. */
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
            boolean attempted = execution.response().provenance() != null
                    && execution.response().provenance().llmAttempted();
            return AskResponse.notFound(execution.response().contextOmitted(),
                    AskReason.CONSENT_REQUIRED, true, RETRY_AFTER_TRANSIENT_SECONDS,
                    execution.response().evidenceOmitted(), attempted);
        }
        if (execution.speaker() != null && (!memberRepository.findJoinedAt(execution.conversationId(), userId)
                .filter(execution.speaker().admittedFrom()::equals).isPresent()
                || !retrievalService.speakerUnchanged(execution.conversationId(), execution.speaker()))) {
            stats.recordNotFound();
            return contextChangedResponse(execution.response().contextOmitted(), llmAttempted(execution.response()));
        }
        return execution.response();
    }

    AskExecution askForResponse(UUID conversationId, UUID userId, AskRequest request) {
        AskDeadline deadline = AskDeadline.start(properties.getAsk().getDeadlineMs());
        stats.recordRequest();
        rateLimiter.consume(userId);
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        Instant admittedFrom = memberRepository.findJoinedAt(conversationId, userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_MEMBER, "Conversation membership required"));
        RagQuestionCache.ContextSnapshot context = questionCache.contextSnapshot(conversationId);
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
            return new AskExecution(consentSnapshot, AskResponse.notFound(
                    contextOmitted, AskReason.RAG_DISABLED, false, null, false, false));
        }

        String question = request.question().strip();
        List<AskPriorTurn> priorTurns = request.priorTurns();
        SummaryLanguage language = request.language() == null || request.language() == SummaryLanguage.AUTO
                ? detectQuestionLanguage(question)
                : request.language();
        String questionHash = RagHashes.questionHash(hashInput(question, priorTurns));

        AskExecution execution = computeForSnapshot(conversationId, userId, question, language,
                questionHash, consentSnapshot, contextOmitted, admittedFrom, priorTurns, context, deadline);
        if (questionCache.contextUnchanged(conversationId, context)) {
            return execution;
        }

        // A source mutation made the just-computed visibility snapshot obsolete. Cache and
        // local extractive work is cheap enough to recompute exactly once; once an LLM was
        // already invoked for the obsolete snapshot, avoid buying a duplicate completion and
        // return retryable CONTEXT_CHANGED rather than pretending there was no evidence.
        if (deadline.expired()) {
            return new AskExecution(consentSnapshot, timeoutResponse(contextOmitted,
                    llmAttempted(execution.response())), conversationId, context);
        }
        if (!execution.response().usage().cached() && llmAttempted(execution.response())) {
            // Do not purchase a duplicate provider completion for a source snapshot that is
            // already obsolete. The typed retryable result lets the client retry promptly.
            return new AskExecution(consentSnapshot, contextChangedResponse(
                    contextOmitted, true), conversationId, context);
        }
        boolean encryptedNow = conversationRepository.findById(conversationId)
                .map(Conversation::isE2eeEnabled).orElse(false);
        if (encryptedNow) {
            throw ApiException.of(ApiErrorCode.E2EE_AI_UNAVAILABLE,
                    "AI features are unavailable in encrypted conversations");
        }
        RagQuestionCache.ContextSnapshot freshContext = questionCache.contextSnapshot(conversationId);
        if (freshContext.equals(context)) {
            return new AskExecution(consentSnapshot, contextChangedResponse(
                    contextOmitted, llmAttempted(execution.response())), conversationId, context);
        }
        SummaryConsentRepository.ConsentSnapshot freshConsent = consentRepository.snapshot(userId);
        boolean freshOmitted = aiOptOuts.hasOptedOutMessages(conversationId);
        AskExecution recomputed = computeForSnapshot(conversationId, userId, question, language,
                questionHash, freshConsent, freshOmitted, admittedFrom, priorTurns, freshContext, deadline);
        if (!questionCache.contextUnchanged(conversationId, freshContext)) {
            stats.recordNotFound();
            return new AskExecution(freshConsent, contextChangedResponse(
                    freshOmitted, llmAttempted(recomputed.response())), conversationId, freshContext);
        }
        return recomputed;
    }

    private AskExecution computeForSnapshot(
            UUID conversationId,
            UUID userId,
            String question,
            SummaryLanguage language,
            String questionHash,
            SummaryConsentRepository.ConsentSnapshot consentSnapshot,
            boolean contextOmitted,
            Instant admittedFrom,
            List<AskPriorTurn> priorTurns,
            RagQuestionCache.ContextSnapshot context,
            AskDeadline deadline) {
        AskSpeakerLookup namedLookup = AskSpeakerLookup.parse(question);
        AskSpeakerLookup.Binding speaker = null;
        if (namedLookup != null) {
            if (deadline.expired()) {
                stats.recordNotFound();
                return new AskExecution(consentSnapshot, timeoutResponse(contextOmitted, false), conversationId, context);
            }
            UUID resolved = namedLookup.ambiguous() ? null
                    : retrievalService.findSpeaker(conversationId, admittedFrom, namedLookup.name());
            if (deadline.expired() || resolved == null) {
                stats.recordNotFound();
                AskResponse response = deadline.expired() ? timeoutResponse(contextOmitted, false)
                        : AskResponse.notFound(contextOmitted, AskReason.NO_EVIDENCE, false, null, false, false);
                return new AskExecution(consentSnapshot, response, conversationId, context);
            }
            speaker = new AskSpeakerLookup.Binding(namedLookup.name(), resolved, admittedFrom);
        }
        AskSpeakerLookup.Binding binding = speaker;
        String evidenceQuestion = namedLookup == null ? question : namedLookup.question();
        List<AskPriorTurn> evidenceHistory = namedLookup == null ? priorTurns : List.of();
        AskKey key = new AskKey(conversationId, userId, questionHash, language, consentSnapshot,
                admittedFrom, context, binding == null ? null : binding.senderId());
        AskResponse response = computeOnce(key, contextOmitted, deadline, () -> compute(
                conversationId, userId, evidenceQuestion, language, questionHash, consentSnapshot, contextOmitted,
                admittedFrom, evidenceHistory, context, deadline, binding));
        return new AskExecution(consentSnapshot, response, conversationId, context,
                response.mode() == AskMode.NOT_FOUND ? null : binding);
    }

    /** Single-flight: the first caller for a key computes and concurrent callers receive that result. */
    private AskResponse computeOnce(
            AskKey key,
            boolean contextOmitted,
            AskDeadline deadline,
            Supplier<AskResponse> computation) {
        CompletableFuture<AskResponse> leader = new CompletableFuture<>();
        CompletableFuture<AskResponse> pending = inFlight.putIfAbsent(key, leader);
        if (pending != null) {
            return await(key, pending, contextOmitted, deadline);
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

    private AskResponse await(
            AskKey key,
            CompletableFuture<AskResponse> pending,
            boolean contextOmitted,
            AskDeadline deadline) {
        long waitMs = properties.getAsk().getSingleFlightWaitMs();
        if (deadline != null) {
            waitMs = Math.min(waitMs, Math.max(0, deadline.remainingMs()));
        }
        try {
            return pending.get(Math.max(1, waitMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            // Detach only the observed stalled leader. Its late finally must not remove a replacement.
            inFlight.remove(key, pending);
            stats.recordNotFound();
            if (deadline != null && deadline.expired()) {
                return timeoutResponse(contextOmitted, false);
            }
            return AskResponse.notFound(contextOmitted, AskReason.SINGLE_FLIGHT_TIMEOUT,
                    true, RETRY_AFTER_TRANSIENT_SECONDS, false, false);
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
            Instant admittedFrom,
            List<AskPriorTurn> priorTurns,
            RagQuestionCache.ContextSnapshot context,
            AskDeadline deadline,
            AskSpeakerLookup.Binding speaker) {
        AskResponse completed = null;
        try {
            completed = computeWithin(conversationId, userId, question, language, questionHash,
                    consentSnapshot, contextOmitted, admittedFrom, priorTurns, context, deadline, speaker);
            if (deadline != null) {
                deadline.check();
            }
            return completed;
        } catch (AskDeadlineExceededException exception) {
            stats.recordNotFound();
            return timeoutResponse(contextOmitted,
                    completed != null && completed.evidenceOmitted(), llmAttempted(completed));
        }
    }

    private AskResponse computeWithin(
            UUID conversationId,
            UUID userId,
            String question,
            SummaryLanguage language,
            String questionHash,
            SummaryConsentRepository.ConsentSnapshot consentSnapshot,
            boolean contextOmitted,
            Instant admittedFrom,
            List<AskPriorTurn> priorTurns,
            RagQuestionCache.ContextSnapshot context,
            AskDeadline deadline,
            AskSpeakerLookup.Binding speaker) {
        check(deadline);
        boolean consented = consentSnapshot.consented();
        // Cached answers are shared per conversation and may cite or paraphrase content from
        // before this member's admission, so a late or returning member always recomputes.
        // Profile renames are outside the message revision. Skip answer replay and storage for named lookups.
        boolean sharedCacheVisible = speaker == null && !retrievalService.hasContentBeforeAdmission(conversationId, admittedFrom);
        RagEmbeddingService.EmbeddingOutcome questionEmbedding = speaker == null
                ? embeddingService.embedQuestion(userId, question, consented, deadline) : null;
        boolean requiresAnswerEvidence = speaker != null || !isBroadAggregationQuestion(question);

        if (consented && sharedCacheVisible) {
            Optional<RagQuestionCache.CachedAsk> exact = questionCache.findExact(
                    conversationId, questionEmbedding.model(), questionHash);
            if (exact.isPresent()
                    && exact.get().expectedContentVersion() == context.revision()
                    && exact.get().language() == language
                    && questionCache.citationsValid(exact.get().citedIds(), exact.get().citedHash())) {
                stats.recordCacheHit();
                return cachedResponse(exact.get(), contextOmitted, "exact");
            }

            double semanticThreshold = properties.getRetrieval().getSemanticCacheThreshold();
            if (!requiresAnswerEvidence && priorTurns.isEmpty()) {
                for (RagQuestionCache.CachedQuestion candidate : questionCache.findCandidates(
                        conversationId, questionEmbedding.model())) {
                    if (AskMode.LLM.name().equals(candidate.ask().mode())
                            && VectorCodec.cosine(questionEmbedding.vector(), candidate.vector()) >= semanticThreshold
                            && candidate.ask().expectedContentVersion() == context.revision()
                            && candidate.ask().language() == language
                            && questionCache.citationsValid(candidate.ask().citedIds(), candidate.ask().citedHash())) {
                        stats.recordCacheHit();
                        return cachedResponse(candidate.ask(), contextOmitted, "semantic");
                    }
                }
            }
        }

        boolean lookupLike = speaker != null || isLookupLike(question);
        check(deadline);
        RetrievalResult retrieval = speaker == null ? retrievalService.retrieve(
                conversationId, userId, question, language, consented, admittedFrom,
                requiresAnswerEvidence, deadline) : retrievalService.retrieveForSpeaker(
                conversationId, userId, question, language, consented, speaker, deadline);
        boolean evidenceOmitted = contextOmitted && retrieval.optedOutEvidenceOverlap();
        if (retrieval.isEmpty()) {
            stats.recordNotFound();
            return AskResponse.notFound(contextOmitted, AskReason.NO_EVIDENCE,
                    false, null, evidenceOmitted, false);
        }
        if (retrieval.bestScore() < properties.getRetrieval().getNotFoundThreshold()) {
            stats.recordNotFound();
            return AskResponse.notFound(contextOmitted, AskReason.LOW_SCORE,
                    false, null, evidenceOmitted, false);
        }

        if (requiresAnswerEvidence && RagExtractiveAnswerer.hasConflictingClaims(
                question, language, retrieval.lookupCandidates(), deadline, speaker != null)) {
            stats.recordNotFound();
            return AskResponse.notFound(contextOmitted, AskReason.NO_EVIDENCE,
                    false, null, evidenceOmitted, false);
        }

        ExtractiveSelection extractive = selectExtractive(question, language, retrieval, speaker != null);
        if (extractive != null
                && retrieval.bestScore() >= properties.getRetrieval().getExtractiveThreshold()
                && canAnswerDirectly(extractive, lookupLike)) {
            return answerExtractive(conversationId, questionEmbedding, questionHash, language, retrieval,
                    extractive, userId, consentSnapshot, contextOmitted, evidenceOmitted, context,
                    null, false);
        }

        if (RagVietnamesePlaceLookup.parse(question, language, speaker != null) != null) {
            stats.recordNotFound();
            return AskResponse.notFound(contextOmitted, AskReason.NO_EVIDENCE,
                    false, null, evidenceOmitted, false);
        }

        RagLlmOutcome outcome = null;
        if (consented) {
            if (speaker != null && !retrievalService.speakerUnchanged(conversationId, speaker)) {
                stats.recordNotFound();
                return contextChangedResponse(contextOmitted, false);
            }
            check(deadline);
            outcome = speaker == null ? llmAnswerService.answerDetailed(
                    userId, conversationId, question, language, retrieval.context(), retrieval.messages(),
                    priorTurns, deadline) : llmAnswerService.answerSpeakerQuote(
                    userId, conversationId, question, language, retrieval.context(), retrieval.messages(), deadline);
            if (outcome.status() == RagLlmOutcome.Status.ANSWER && outcome.answer() != null) {
                boolean citesNamedSpeaker = speaker == null || outcome.answer().citationIds().stream().allMatch(id -> {
                    RagMessage source = retrieval.messages().get(id);
                    return source != null && speaker.senderId().equals(source.senderId());
                });
                RagQuestionCache.CachedAsk answer = citesNamedSpeaker ? toCachedAsk(
                        outcome.answer(), retrieval.messages(), retrieval.context(), AskMode.LLM, language,
                        context, question, null, true, outcome.sensitiveContentRedacted()) : null;
                if (answer != null) {
                    saveAnswer(userId, consentSnapshot, conversationId, questionEmbedding, questionHash, answer);
                    stats.recordLlmCall(retrieval.inputChars(), "fallback".equals(outcome.answer().provider()));
                    return toResponse(answer, AskMode.LLM, 1, retrieval.inputChars(), false,
                            outcome.answer().provider(), contextOmitted, evidenceOmitted);
                }
                outcome = RagLlmOutcome.providerError(true);
            }
        }

        boolean llmAttempted = outcome != null && outcome.llmAttempted();
        if (outcome != null && outcome.status() == RagLlmOutcome.Status.ABSTAIN) {
            stats.recordNotFound();
            return AskResponse.notFound(contextOmitted, AskReason.NO_EVIDENCE,
                    false, null, evidenceOmitted, true);
        }
        if (outcome != null && outcome.deadlineExceeded()) {
            stats.recordNotFound();
            return timeoutResponse(contextOmitted, evidenceOmitted, outcome.llmAttempted());
        }

        AskReason degradation = outcome == null
                ? AskReason.CONSENT_REQUIRED
                : reasonForOutcome(outcome);
        if (extractive != null
                && retrieval.bestScore() >= properties.getRetrieval().getExtractiveThreshold()
                && canDegradeToExtractive(question, extractive)) {
            return answerExtractive(conversationId, questionEmbedding, questionHash, language, retrieval,
                    extractive, userId, consentSnapshot, contextOmitted, evidenceOmitted, context,
                    degradation, llmAttempted);
        }

        stats.recordNotFound();
        boolean retryable = degradation == AskReason.PROVIDER_ERROR || degradation == AskReason.BULKHEAD_REJECTED;
        Integer retryAfter = degradation == AskReason.BULKHEAD_REJECTED ? RETRY_AFTER_TRANSIENT_SECONDS : null;
        return AskResponse.notFound(contextOmitted, degradation, retryable, retryAfter,
                evidenceOmitted, llmAttempted);
    }

    private AskReason reasonForOutcome(RagLlmOutcome outcome) {
        if (outcome.consentRequired()) {
            return AskReason.CONSENT_REQUIRED;
        }
        if (outcome.contextSuppressed()) {
            return AskReason.CONTEXT_SUPPRESSED;
        }
        if (outcome.status() == RagLlmOutcome.Status.BULKHEAD_REJECTED) {
            return AskReason.BULKHEAD_REJECTED;
        }
        return AskReason.PROVIDER_ERROR;
    }

    private boolean canAnswerDirectly(ExtractiveSelection selection, boolean lookupLike) {
        if (selection.result().exactValue()) {
            return true;
        }
        return lookupLike
                && selection.result().coverage() >= properties.getRetrieval().getLexicalAnswerMinCoverage();
    }

    private boolean canDegradeToExtractive(String question, ExtractiveSelection selection) {
        if (selection.result().exactValue()) {
            return true;
        }
        double requiredCoverage = Math.max(0.8, properties.getRetrieval().getLexicalAnswerMinCoverage());
        return RagExtractiveAnswerer.isFactoidRequest(question)
                && selection.result().coverage() >= requiredCoverage;
    }

    private ExtractiveSelection selectExtractive(
            String question, SummaryLanguage language, RetrievalResult retrieval, boolean namedSpeaker) {
        RagProperties.Retrieval config = properties.getRetrieval();
        List<String> terms = RagQuestionTerms.answerTerms(question, language, config.getLexicalTermLimit());
        ExtractiveSelection firstSpan = null;
        ExtractiveSelection exact = null;
        int limit = Math.min(3, retrieval.ranked().size());
        for (int index = 0; index < limit; index++) {
            RagMessage candidate = retrieval.ranked().get(index).message();
            RagExtractiveAnswerer.Result result = RagExtractiveAnswerer.extract(
                    question, language, terms, candidate, EXTRACTIVE_ANSWER_MAX_CHARS, namedSpeaker);
            if (result == null || result.coverage() < config.getLexicalAnswerMinCoverage()) {
                continue;
            }
            if (result.conflictingValues()) {
                return null;
            }
            ExtractiveSelection selection = new ExtractiveSelection(candidate, result);
            if (result.exactValue()) {
                if (exact != null && (!exact.result().answer().equals(result.answer())
                        || !exact.result().extraction().valueType().equals(result.extraction().valueType()))) {
                    return null;
                }
                if (exact == null) {
                    exact = selection;
                }
            } else if (firstSpan == null) {
                firstSpan = selection;
            }
        }
        return exact == null ? firstSpan : exact;
    }

    private AskResponse answerExtractive(
            UUID conversationId,
            RagEmbeddingService.EmbeddingOutcome questionEmbedding,
            String questionHash,
            SummaryLanguage language,
            RetrievalResult retrieval,
            ExtractiveSelection selection,
            UUID userId,
            SummaryConsentRepository.ConsentSnapshot consentSnapshot,
            boolean contextOmitted,
            boolean evidenceOmitted,
            RagQuestionCache.ContextSnapshot context,
            AskReason degradation,
            boolean llmAttempted) {
        RagQuestionCache.CachedAsk answer = toCachedAsk(
                selection, retrieval.messages(), AskMode.EXTRACTIVE, language, context,
                degradation, llmAttempted);
        if (answer == null) {
            stats.recordNotFound();
            return AskResponse.notFound(contextOmitted, AskReason.NO_EVIDENCE,
                    false, null, evidenceOmitted, llmAttempted);
        }
        saveAnswer(userId, consentSnapshot, conversationId, questionEmbedding, questionHash, answer);
        stats.recordExtractive();
        return toResponse(answer, AskMode.EXTRACTIVE, 0,
                llmAttempted ? retrieval.inputChars() : 0, false, null, contextOmitted, evidenceOmitted);
    }

    private void saveAnswer(
            UUID userId,
            SummaryConsentRepository.ConsentSnapshot consentSnapshot,
            UUID conversationId,
            RagEmbeddingService.EmbeddingOutcome questionEmbedding,
            String questionHash,
            RagQuestionCache.CachedAsk answer) {
        if (questionEmbedding == null) {
            return;
        }
        try {
            questionCache.saveIfConsentSnapshotMatches(userId, consentSnapshot, conversationId,
                    questionEmbedding.model(), questionHash, questionEmbedding.vector(), answer);
        } catch (RuntimeException exception) {
            // A cache write failure must never fail the answer.
            log.debug("ask cache write failed: {}", exception.getClass().getSimpleName());
        }
    }

    private RagQuestionCache.CachedAsk toCachedAsk(
            ExtractiveSelection selection,
            Map<UUID, RagMessage> messages,
            AskMode mode,
            SummaryLanguage language,
            RagQuestionCache.ContextSnapshot context,
            AskReason degradation,
            boolean llmAttempted) {
        RagMessage message = selection.message();
        if (message == null || messages.get(message.id()) == null) {
            return null;
        }
        RagCitation citation = new RagCitation(message.id(), message.seq(),
                snippet(message.content(), selection.result().extraction().startOffset()),
                RagCitation.Role.PRIMARY);
        AskProvenance provenance = AskProvenance.origin(mode, null, degradation, llmAttempted);
        return new RagQuestionCache.CachedAsk(
                selection.result().answer(),
                List.of(citation),
                mode.name(),
                List.of(message.id()),
                RagHashes.citationsHash(List.of(message)),
                language,
                context.revision(), context.expiresAt(),
                provenance,
                false,
                selection.result().extraction());
    }

    private RagQuestionCache.CachedAsk toCachedAsk(
            RagLlmAnswer llmAnswer,
            Map<UUID, RagMessage> messages,
            List<RagContextItem> context,
            AskMode mode,
            SummaryLanguage language,
            RagQuestionCache.ContextSnapshot contextSnapshot,
            String question,
            AskReason degradation,
            boolean llmAttempted,
            boolean sensitiveContentRedacted) {
        List<RagCitation> citations = new ArrayList<>();
        List<UUID> citedIds = new ArrayList<>();
        List<RagMessage> citedMessages = new ArrayList<>();
        for (UUID citationId : llmAnswer.citationIds()) {
            RagMessage message = messages.get(citationId);
            if (message == null || citedIds.contains(citationId)) {
                continue;
            }
            citations.add(new RagCitation(citationId, message.seq(),
                    snippet(message.content(), question),
                    citationRole(context, citationId)));
            citedIds.add(citationId);
            citedMessages.add(message);
        }
        if (citations.isEmpty()) {
            return null;
        }
        String generationProvider = llmAnswer.provider() == null ? "local" : llmAnswer.provider();
        AskProvenance provenance = AskProvenance.origin(
                mode, generationProvider, degradation, llmAttempted);
        return new RagQuestionCache.CachedAsk(
                llmAnswer.answer(),
                citations,
                mode.name(),
                citedIds,
                RagHashes.citationsHash(citedMessages),
                language,
                contextSnapshot.revision(), contextSnapshot.expiresAt(),
                provenance,
                sensitiveContentRedacted,
                null);
    }

    private RagCitation.Role citationRole(List<RagContextItem> context, UUID citationId) {
        if (context != null) {
            for (RagContextItem item : context) {
                if (item.primaryMessageIds().contains(citationId)) {
                    return RagCitation.Role.PRIMARY;
                }
            }
        }
        return RagCitation.Role.CONTEXT;
    }

    private AskResponse cachedResponse(
            RagQuestionCache.CachedAsk answer, boolean contextOmitted, String cacheType) {
        AskProvenance provenance = answer.provenance();
        if (provenance == null) {
            AskMode origin = parseMode(answer.mode());
            provenance = AskProvenance.origin(origin, null, null, origin == AskMode.LLM);
        }
        // A replay is not a fresh degradation signal. Provenance retains why the
        // original answer was degraded, but this response must not invite a retry
        // that deterministically hits the same cache entry.
        return new AskResponse(
                answer.answer(),
                answer.citations(),
                AskMode.CACHE,
                contextOmitted,
                new AskResponse.Usage(0, 0, true),
                null,
                null,
                false,
                null,
                provenance.withCacheType(cacheType),
                false,
                false,
                answer.sensitiveContentRedacted(),
                answer.extraction());
    }

    private AskMode parseMode(String value) {
        try {
            return AskMode.valueOf(value);
        } catch (RuntimeException exception) {
            return AskMode.EXTRACTIVE;
        }
    }

    private AskResponse toResponse(
            RagQuestionCache.CachedAsk answer,
            AskMode mode,
            int llmCalls,
            int inputChars,
            boolean cached,
            String provider,
            boolean contextOmitted,
            boolean evidenceOmitted) {
        AskProvenance provenance = answer.provenance() == null
                ? AskProvenance.origin(mode, provider, null, llmCalls > 0)
                : answer.provenance();
        return new AskResponse(
                answer.answer(),
                answer.citations(),
                mode,
                contextOmitted,
                new AskResponse.Usage(llmCalls, inputChars, cached, provider),
                null,
                provenance.degradationReason(),
                provenance.degradationReason() == AskReason.BULKHEAD_REJECTED
                        || provenance.degradationReason() == AskReason.PROVIDER_ERROR,
                null,
                provenance,
                evidenceOmitted,
                evidenceOmitted,
                answer.sensitiveContentRedacted(),
                answer.extraction());
    }

    private AskResponse timeoutResponse(boolean contextOmitted, boolean llmAttempted) {
        return timeoutResponse(contextOmitted, false, llmAttempted);
    }

    private AskResponse timeoutResponse(
            boolean contextOmitted, boolean evidenceOmitted, boolean llmAttempted) {
        return AskResponse.notFound(contextOmitted, AskReason.TIMEOUT,
                true, RETRY_AFTER_TRANSIENT_SECONDS, evidenceOmitted, llmAttempted);
    }

    private AskResponse contextChangedResponse(boolean contextOmitted, boolean llmAttempted) {
        return AskResponse.notFound(contextOmitted, AskReason.CONTEXT_CHANGED,
                true, RETRY_AFTER_TRANSIENT_SECONDS, false, llmAttempted);
    }

    private static boolean llmAttempted(AskResponse response) {
        return response != null && response.provenance() != null && response.provenance().llmAttempted();
    }

    private static void check(AskDeadline deadline) {
        if (deadline != null) {
            deadline.check();
        }
    }

    private boolean isBroadAggregationQuestion(String question) {
        String folded = RagQuestionNormalizer.normalize(question);
        String withoutPoliteness = folded.replaceFirst(
                "^(?:(?:please|can you|could you|would you|will you|help me|vui long|cho toi|cho minh|giup toi|giup minh)\\s+)+",
                "");
        boolean explicitAggregation = withoutPoliteness.matches(
                "^(summarize|summary|recap|catch me up|tom tat|tong hop|tong quan)(\\s|$).*")
                || withoutPoliteness.matches("^list (the )?(action items|tasks|decisions)(\\s|$).*")
                || withoutPoliteness.matches("^(liet ke|cac quyet dinh|nhung quyet dinh)(\\s|$).*")
                || withoutPoliteness.matches("^(what did we decide|what was decided)(\\s|$).*");
        if (explicitAggregation) {
            return true;
        }
        boolean openEndedScope = folded.contains(" any other relevant ")
                || folded.contains(" every other relevant ")
                || folded.contains(" anything else")
                || folded.contains(" everything else");
        boolean corpusAnchor = folded.contains(" team discussion")
                || folded.contains(" conversation")
                || folded.contains(" meeting")
                || folded.contains(" we discussed together");
        return folded.contains(" and ") && openEndedScope && corpusAnchor;
    }

    private boolean isLookupLike(String question) {
        if (question.codePointCount(0, question.length()) > LOOKUP_QUESTION_MAX_CHARS) {
            return false;
        }
        return CueDetector.isQuestion(new SummaryMessage("probe", 0, "probe", "", question))
                || RagVietnameseDateLookup.isDateQuestion(question)
                || RagQuestionTerms.hasVietnamesePlaceOrTimeCue(question);
    }

    private SummaryLanguage detectQuestionLanguage(String question) {
        com.nexachat.summary.engine.SummaryLanguage detected = LanguageDetector.detect(
                new SummaryWindow(List.of(new SummaryMessage("question", 0, "user", "", question))));
        return SummaryLanguage.valueOf(detected.name());
    }

    /** Query-centered snippet; when a span offset is known, center around that exact evidence. */
    private String snippet(String content, String question) {
        return snippet(content, pivotFor(content, question));
    }

    private String snippet(String content, int pivot) {
        FoldedContent folded = foldContent(content);
        String text = folded.text();
        int max = properties.getRetrieval().getMaxSnippetChars();
        int length = text.codePointCount(0, text.length());
        if (length <= max) {
            return text;
        }
        int pivotIndex = folded.indexForRawOffset(pivot);
        int pivotCodePoint = text.codePointCount(0, pivotIndex);
        int startCodePoint = Math.max(0, pivotCodePoint - max / 3);
        startCodePoint = Math.min(startCodePoint, Math.max(0, length - max));
        int endCodePoint = Math.min(length, startCodePoint + max);
        int start = text.offsetByCodePoints(0, startCodePoint);
        int end = text.offsetByCodePoints(0, endCodePoint);
        String snippet = text.substring(start, end);
        if (start > 0) {
            snippet = "…" + snippet;
        }
        if (end < text.length()) {
            snippet = snippet + "…";
        }
        return snippet;
    }

    /**
     * Fold whitespace exactly like the rendered snippet while preserving a raw-to-folded
     * offset map. Extraction and query pivots are raw-content offsets, so the window must
     * account for whitespace removed before the pivot instead of reusing the raw index.
     */
    private FoldedContent foldContent(String content) {
        String raw = content == null ? "" : content;
        int[] rawToFolded = new int[raw.length() + 1];
        StringBuilder folded = new StringBuilder(raw.length());
        int index = 0;
        while (index < raw.length()) {
            if (isSnippetWhitespace(raw.charAt(index))) {
                rawToFolded[index] = folded.length();
                while (index < raw.length() && isSnippetWhitespace(raw.charAt(index))) {
                    index++;
                    rawToFolded[index] = folded.length() + 1;
                }
                folded.append(' ');
            } else {
                rawToFolded[index] = folded.length();
                folded.append(raw.charAt(index));
                index++;
                rawToFolded[index] = folded.length();
            }
        }
        rawToFolded[raw.length()] = folded.length();

        int leadingTrim = 0;
        while (leadingTrim < folded.length() && folded.charAt(leadingTrim) <= ' ') {
            leadingTrim++;
        }
        int end = folded.length();
        while (end > leadingTrim && folded.charAt(end - 1) <= ' ') {
            end--;
        }
        return new FoldedContent(folded.substring(leadingTrim, end), rawToFolded, leadingTrim);
    }

    /** The whitespace characters matched by the regex {@code \s} in Java. */
    private static boolean isSnippetWhitespace(char current) {
        return current == ' ' || current == '\t' || current == '\n'
                || current == '\u000b' || current == '\f' || current == '\r';
    }

    private int pivotFor(String content, String question) {
        if (content == null || question == null) {
            return 0;
        }
        int best = -1;
        for (String term : RagQuestionTerms.extract(question, SummaryLanguage.AUTO, 8)) {
            int position = indexOfIgnoreCase(content, term);
            if (position >= 0 && (best < 0 || position < best)) {
                best = position;
            }
        }
        return Math.max(0, best);
    }

    /** Case-insensitive search whose result remains a raw-content UTF-16 offset. */
    private static int indexOfIgnoreCase(String content, String term) {
        if (term == null || term.isEmpty()) {
            return -1;
        }
        Matcher matcher = Pattern.compile(Pattern.quote(term),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(content);
        return matcher.find() ? matcher.start() : -1;
    }

    static String hashInput(String question, List<AskPriorTurn> priorTurns) {
        String normalized = RagQuestionNormalizer.normalizeForCache(question);
        String fingerprint = AskPriorTurns.fingerprint(priorTurns);
        if (fingerprint.isEmpty()) {
            return normalized;
        }
        return normalized + "\nprior:" + fingerprint;
    }

    private record FoldedContent(String text, int[] rawToFolded, int leadingTrim) {

        int indexForRawOffset(int rawOffset) {
            int clamped = Math.max(0, Math.min(rawOffset, rawToFolded.length - 1));
            int mapped = rawToFolded[clamped] - leadingTrim;
            return Math.max(0, Math.min(mapped, text.length()));
        }
    }

    private record ExtractiveSelection(RagMessage message, RagExtractiveAnswerer.Result result) {
    }

    private record AskKey(
            UUID conversationId,
            UUID userId,
            String questionHash,
            SummaryLanguage language,
            SummaryConsentRepository.ConsentSnapshot consentSnapshot,
            Instant admittedFrom,
            RagQuestionCache.ContextSnapshot context,
            UUID namedSenderId) {
    }
}
