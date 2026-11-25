package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.engine.Mmr;
import com.nexachat.summary.engine.SummaryCandidate;
import com.nexachat.summary.engine.SummaryMessage;
import com.nexachat.summary.engine.SummarySignals;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hybrid retrieval: pg_trgm lexical candidates plus vector cosine, fused with
 * reciprocal-rank fusion, diversified with MMR and expanded into a budgeted
 * prompt context.
 */
@Service
@Profile("!test")
public class RagRetrievalService {

    private final RagMessageIndex index;
    private final RagEmbeddingService embeddings;
    private final RagProperties properties;

    public RagRetrievalService(
            RagMessageIndex index,
            RagEmbeddingService embeddings,
            RagProperties properties) {
        this.index = index;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    public RetrievalResult retrieve(
            UUID conversationId, String question, SummaryLanguage language, boolean consented) {
        return retrieve(conversationId, question, language, consented, null);
    }

    /**
     * Retrieves only messages created at or after {@code admittedFrom}, the asking member's
     * admission time, so an answer never draws on content from before they joined. Null means
     * no bound.
     */
    public RetrievalResult retrieve(
            UUID conversationId, String question, SummaryLanguage language, boolean consented,
            java.time.Instant admittedFrom) {
        return retrieve(conversationId, question, language, consented, admittedFrom, false);
    }

    /**
     * When {@code requireAnswerEvidence} is true, rank evidence alone is not
     * enough: at least one candidate must share sufficient question-term
     * coverage, or the raw vector evidence must clear the configured
     * answerability threshold. This keeps reciprocal-rank positions useful for ordering without
     * allowing the recent-message window to manufacture answerability.
     */
    public RetrievalResult retrieve(
            UUID conversationId, String question, SummaryLanguage language, boolean consented,
            java.time.Instant admittedFrom, boolean requireAnswerEvidence) {
        return retrieve(conversationId, null, question, language, consented, admittedFrom, requireAnswerEvidence);
    }

    /** User-aware overload so external embedding calls re-check live requester consent. */
    public RetrievalResult retrieve(
            UUID conversationId, UUID userId, String question, SummaryLanguage language, boolean consented,
            java.time.Instant admittedFrom, boolean requireAnswerEvidence) {
        return retrieve(conversationId, userId, question, language, consented,
                admittedFrom, requireAnswerEvidence, null);
    }

    /** Deadline-aware variant used by the synchronous ask path. */
    public RetrievalResult retrieve(
            UUID conversationId, UUID userId, String question, SummaryLanguage language, boolean consented,
            java.time.Instant admittedFrom, boolean requireAnswerEvidence, AskDeadline deadline) {
        check(deadline);
        RagProperties.Retrieval config = properties.getRetrieval();
        List<RagMessage> recent = index.recentMessages(
                conversationId, config.getMaxCandidateMessages(), admittedFrom);
        List<String> terms = RagQuestionTerms.extract(question, language, config.getLexicalTermLimit());
        List<RagMessage> lexicalCandidates = index.lexicalCandidates(
                conversationId, terms, config.getLexicalCandidateLimit(), admittedFrom);
        boolean optedOutEvidenceOverlap = requireAnswerEvidence
                ? index.hasOptedOutLexicalCandidates(conversationId, terms, admittedFrom)
                : index.hasOptedOutMessages(conversationId, admittedFrom);
        if (recent.isEmpty()) {
            return empty(optedOutEvidenceOverlap);
        }

        Map<UUID, RagMessage> vectorCandidateMap = new LinkedHashMap<>();
        int recentWindow = Math.min(config.getVectorCandidateLimit(), recent.size());
        for (int position = recent.size() - recentWindow; position < recent.size(); position++) {
            RagMessage message = recent.get(position);
            vectorCandidateMap.put(message.id(), message);
        }
        for (RagMessage message : lexicalCandidates) {
            vectorCandidateMap.put(message.id(), message);
        }
        List<RagMessage> vectorCandidates = List.copyOf(vectorCandidateMap.values());

        check(deadline);
        int inlineEmbeddingBudget = Math.min(
                properties.getIndex().getLazyLimit(),
                properties.getAsk().getMaxInlineEmbeddingComputations());
        RagEmbeddingService.RetrievalEmbeddings embedded = embeddings.embedForRetrieval(
                userId, question, vectorCandidates, inlineEmbeddingBudget, consented, deadline);
        check(deadline);
        if (requireAnswerEvidence && !hasAnswerEvidence(
                question, language, lexicalCandidates, vectorCandidates, embedded, config)) {
            return empty(optedOutEvidenceOverlap);
        }

        List<ScoredMessage> lexicalRanked = RagScorer.lexicalRank(lexicalCandidates, question, language);
        List<ScoredMessage> vectorRanked = RagScorer.vectorRank(
                vectorCandidates, embedded.vectors(), embedded.questionVector());
        List<ScoredMessage> fused = RagScorer.fuse(lexicalRanked, vectorRanked, config.getRrfK());
        if (fused.isEmpty()) {
            return empty(optedOutEvidenceOverlap);
        }

        Map<String, RagMessage> messagesById = new LinkedHashMap<>();
        for (ScoredMessage scored : fused) {
            messagesById.put(scored.message().id().toString(), scored.message());
        }
        List<SummaryCandidate> mmrInput = fused.stream()
                .map(scored -> toSummaryCandidate(scored, language))
                .toList();
        List<SummaryCandidate> selected = Mmr.select(
                mmrInput, config.getMmrLimit(), config.getMmrLambda());
        List<ScoredMessage> ranked = selected.stream()
                .map(candidate -> new ScoredMessage(
                        messagesById.get(candidate.message().id()), candidate.score()))
                .filter(scored -> scored.message() != null)
                .toList();
        if (ranked.isEmpty()) {
            return empty(optedOutEvidenceOverlap);
        }

        check(deadline);
        RagContextBuilder.ContextResult contextResult = RagContextBuilder.build(
                ranked, recent, index, config, admittedFrom);
        check(deadline);
        return new RetrievalResult(
                ranked,
                ranked.get(0).message(),
                contextResult.context(),
                ranked.get(0).score(),
                contextResult.inputChars(),
                contextResult.messages(),
                optedOutEvidenceOverlap);
    }

    private static RetrievalResult empty(boolean optedOutEvidenceOverlap) {
        if (!optedOutEvidenceOverlap) {
            return RetrievalResult.empty();
        }
        return new RetrievalResult(List.of(), null, List.of(), 0.0, 0, Map.of(), true);
    }

    private static void check(AskDeadline deadline) {
        if (deadline != null) {
            deadline.check();
        }
    }

    private boolean hasAnswerEvidence(
            String question,
            SummaryLanguage language,
            List<RagMessage> lexicalCandidates,
            List<RagMessage> vectorCandidates,
            RagEmbeddingService.RetrievalEmbeddings embedded,
            RagProperties.Retrieval config) {
        if (bestLexicalCoverage(question, language, lexicalCandidates, config)
                >= config.getLexicalAnswerMinCoverage()) {
            return true;
        }
        if (embedded == null || embedded.questionVector() == null) {
            return false;
        }
        double best = 0.0;
        for (RagMessage candidate : vectorCandidates) {
            float[] vector = embedded.vectors() == null ? null : embedded.vectors().get(candidate.id());
            if (vector == null) {
                continue;
            }
            best = Math.max(best, VectorCodec.cosine(embedded.questionVector(), vector));
        }
        return best >= config.getVectorAnswerThreshold();
    }

    private double bestLexicalCoverage(
            String question,
            SummaryLanguage language,
            List<RagMessage> lexicalCandidates,
            RagProperties.Retrieval config) {
        Set<String> questionTerms = new HashSet<>(RagQuestionTerms.extract(
                question, language, config.getLexicalTermLimit()));
        if (questionTerms.isEmpty()) {
            return 0.0;
        }
        double best = 0.0;
        for (RagMessage candidate : lexicalCandidates) {
            Set<String> messageTerms = new HashSet<>(RagQuestionTerms.contentTerms(candidate.content(), language));
            int matched = 0;
            for (String term : questionTerms) {
                if (containsMatchingTerm(messageTerms, term)) {
                    matched++;
                }
            }
            best = Math.max(best, (double) matched / questionTerms.size());
        }
        return best;
    }

    private static boolean containsMatchingTerm(Set<String> messageTerms, String questionTerm) {
        if (messageTerms.contains(questionTerm)) {
            return true;
        }
        if (questionTerm.length() < 5) {
            return false;
        }
        for (String messageTerm : messageTerms) {
            if (messageTerm.length() >= 5
                    && Math.abs(messageTerm.length() - questionTerm.length()) <= 3
                    && (messageTerm.startsWith(questionTerm) || questionTerm.startsWith(messageTerm))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the conversation holds content from before {@code admittedFrom}. Answers cached
     * for the conversation may draw on that content, so such a member must not be served them.
     */
    public boolean hasContentBeforeAdmission(UUID conversationId, java.time.Instant admittedFrom) {
        return index.hasMessagesBefore(conversationId, admittedFrom);
    }
    private static SummaryCandidate toSummaryCandidate(ScoredMessage scored, SummaryLanguage language) {
        RagMessage message = scored.message();
        SummaryMessage summaryMessage = new SummaryMessage(
                message.id().toString(),
                message.seq(),
                message.senderId() == null ? null : message.senderId().toString(),
                message.senderName(),
                message.content(),
                message.createdAt(),
                message.editedAt(),
                SummarySignals.none());
        List<String> terms = RagQuestionTerms.contentTerms(message.content(), language);
        return new SummaryCandidate(summaryMessage, terms, terms, scored.score());
    }
}
