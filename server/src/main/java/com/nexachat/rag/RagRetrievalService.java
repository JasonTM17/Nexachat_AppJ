package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.engine.Mmr;
import com.nexachat.summary.engine.SummaryCandidate;
import com.nexachat.summary.engine.SummaryMessage;
import com.nexachat.summary.engine.SummarySignals;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
     * When {@code requireAnswerEvidence} is true, rank or vector evidence alone is not
     * enough: the essential question terms must co-occur in one candidate. Bounded fuzzy
     * matching protects typo/diacritic variants, while vector similarity can support a
     * fuzzy candidate but cannot manufacture answerability by itself.
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
        return retrieve(conversationId, userId, question, language, consented, admittedFrom,
                requireAnswerEvidence, deadline, null);
    }

    UUID findSpeaker(UUID conversationId, Instant admittedFrom, String name) {
        List<UUID> candidates = index.findEligibleSpeakerIds(conversationId, admittedFrom, name);
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    boolean speakerUnchanged(UUID conversationId, AskSpeakerLookup.Binding binding) {
        return binding.senderId().equals(findSpeaker(conversationId, binding.admittedFrom(), binding.name()));
    }

    RetrievalResult retrieveForSpeaker(
            UUID conversationId, UUID userId, String question, SummaryLanguage language, boolean consented,
            AskSpeakerLookup.Binding speaker, AskDeadline deadline) {
        return retrieve(conversationId, userId, question, language, consented, speaker.admittedFrom(),
                true, deadline, speaker.senderId());
    }

    private RetrievalResult retrieve(
            UUID conversationId, UUID userId, String question, SummaryLanguage language, boolean consented,
            Instant admittedFrom, boolean requireAnswerEvidence, AskDeadline deadline, UUID senderId) {
        check(deadline);
        RagProperties.Retrieval config = properties.getRetrieval();
        List<RagMessage> recent = senderId == null
                ? index.recentMessages(conversationId, config.getMaxCandidateMessages(), admittedFrom)
                : index.recentMessagesBySpeaker(conversationId, senderId, config.getMaxCandidateMessages(), admittedFrom);
        List<String> terms = RagQuestionTerms.extract(question, language, config.getLexicalTermLimit());
        List<RagMessage> lexicalCandidates = senderId == null
                ? index.lexicalCandidates(conversationId, terms, config.getLexicalCandidateLimit(), admittedFrom)
                : index.lexicalCandidatesBySpeaker(conversationId, senderId, terms, config.getLexicalCandidateLimit(), admittedFrom);
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
                question, language, lexicalCandidates, vectorCandidates, embedded, config, senderId != null)) {
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
        // Named lookups need complete, separately citable statements by just that speaker.
        // Unrestricted neighbors or reply parents would defeat that boundary.
        RagContextBuilder.ContextResult contextResult = senderId == null
                ? RagContextBuilder.build(ranked, recent, index, config, admittedFrom)
                : RagContextBuilder.buildSpeakerContext(ranked, recent, config, admittedFrom);
        check(deadline);
        return new RetrievalResult(
                ranked,
                ranked.get(0).message(),
                contextResult.context(),
                ranked.get(0).score(),
                contextResult.inputChars(),
                contextResult.messages(),
                optedOutEvidenceOverlap,
                // MMR deduplicates similar language, including claims that differ in their value.
                // Keep the bounded, admitted pool for the local lookup check only.
                requireAnswerEvidence ? fused.stream().map(ScoredMessage::message).toList() : List.of());
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
            RagProperties.Retrieval config,
            boolean namedSpeaker) {
        List<String> questionTerms = RagQuestionTerms.answerTerms(question, language, config.getLexicalTermLimit());
        if (questionTerms.isEmpty()) {
            return false;
        }
        Set<UUID> lexicalCandidateIds = new HashSet<>();
        for (RagMessage candidate : lexicalCandidates) {
            lexicalCandidateIds.add(candidate.id());
        }
        boolean needsSettledEvidence = RagExtractiveAnswerer.needsSettledEvidence(question, language);
        double requiredCoverage = config.getLexicalAnswerMinCoverage();
        for (RagMessage candidate : vectorCandidates) {
            List<String> statements = namedSpeaker ? RagExtractiveAnswerer.attributedStatements(candidate.content())
                    : needsSettledEvidence ? RagExtractiveAnswerer.settledStatements(candidate.content())
                    : List.of(candidate.content());
            boolean covered = statements.stream().anyMatch(text -> hasCoverage(text, language, questionTerms, requiredCoverage));
            if (!covered) {
                continue;
            }
            if (lexicalCandidateIds.contains(candidate.id())
                    || vectorCosine(candidate, embedded) >= config.getVectorAnswerThreshold()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCoverage(
            String text, SummaryLanguage language, List<String> questionTerms, double requiredCoverage) {
        Set<String> messageTerms = new HashSet<>(RagQuestionTerms.contentTerms(text, language));
        int matched = 0;
        for (String term : questionTerms) {
            if (containsMatchingTerm(messageTerms, term)) {
                matched++;
            }
        }
        return (double) matched / questionTerms.size() + 0.0000001 >= requiredCoverage;
    }

    private static double vectorCosine(
            RagMessage candidate, RagEmbeddingService.RetrievalEmbeddings embedded) {
        if (embedded == null || embedded.questionVector() == null || embedded.vectors() == null) {
            return 0.0;
        }
        float[] vector = embedded.vectors().get(candidate.id());
        return vector == null ? 0.0 : VectorCodec.cosine(embedded.questionVector(), vector);
    }

    private static boolean containsMatchingTerm(Set<String> messageTerms, String questionTerm) {
        if (messageTerms.contains(questionTerm)) {
            return true;
        }
        if (questionTerm.length() < 5) {
            return false;
        }
        for (String messageTerm : messageTerms) {
            if (messageTerm.length() < 5) {
                continue;
            }
            if (Math.abs(messageTerm.length() - questionTerm.length()) <= 3
                    && (messageTerm.startsWith(questionTerm) || questionTerm.startsWith(messageTerm))) {
                return true;
            }
            int maximumDistance = questionTerm.length() >= 8 ? 2 : 1;
            if (Math.abs(messageTerm.length() - questionTerm.length()) <= maximumDistance
                    && editDistanceAtMost(questionTerm, messageTerm, maximumDistance)) {
                return true;
            }
        }
        return false;
    }

    private static boolean editDistanceAtMost(String first, String second, int maximumDistance) {
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];
        for (int column = 0; column <= second.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= first.length(); row++) {
            current[0] = row;
            int rowMinimum = current[0];
            for (int column = 1; column <= second.length(); column++) {
                int substitution = previous[column - 1]
                        + (first.charAt(row - 1) == second.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(substitution,
                        Math.min(previous[column] + 1, current[column - 1] + 1));
                rowMinimum = Math.min(rowMinimum, current[column]);
            }
            if (rowMinimum > maximumDistance) {
                return false;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[second.length()] <= maximumDistance;
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
