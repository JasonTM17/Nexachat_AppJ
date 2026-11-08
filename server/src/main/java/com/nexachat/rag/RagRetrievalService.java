package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.engine.Mmr;
import com.nexachat.summary.engine.SummaryCandidate;
import com.nexachat.summary.engine.SummaryMessage;
import com.nexachat.summary.engine.SummarySignals;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        RagProperties.Retrieval config = properties.getRetrieval();
        List<RagMessage> recent = index.recentMessages(conversationId, config.getMaxCandidateMessages());
        if (recent.isEmpty()) {
            return RetrievalResult.empty();
        }

        List<String> terms = RagQuestionTerms.extract(question, language, config.getLexicalTermLimit());
        List<RagMessage> lexicalCandidates = index.lexicalCandidates(
                conversationId, terms, config.getLexicalCandidateLimit());

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

        RagEmbeddingService.RetrievalEmbeddings embedded = embeddings.embedForRetrieval(
                question, vectorCandidates, properties.getIndex().getLazyLimit(), consented);

        List<ScoredMessage> lexicalRanked = RagScorer.lexicalRank(lexicalCandidates, question, language);
        List<ScoredMessage> vectorRanked = RagScorer.vectorRank(
                vectorCandidates, embedded.vectors(), embedded.questionVector());
        List<ScoredMessage> fused = RagScorer.fuse(lexicalRanked, vectorRanked, config.getRrfK());
        if (fused.isEmpty()) {
            return RetrievalResult.empty();
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
            return RetrievalResult.empty();
        }

        RagContextBuilder.ContextResult contextResult = RagContextBuilder.build(
                ranked, recent, index, config);
        return new RetrievalResult(
                ranked,
                ranked.get(0).message(),
                contextResult.context(),
                ranked.get(0).score(),
                contextResult.inputChars(),
                contextResult.messages());
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
